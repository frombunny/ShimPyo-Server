package com.hsu.shimpyoo.domain.breathing.service;
import com.hsu.shimpyoo.domain.breathing.web.dto.BreathingPefDto;
import com.hsu.shimpyoo.domain.breathing.entity.Breathing;
import com.hsu.shimpyoo.domain.breathing.entity.*;
import com.hsu.shimpyoo.domain.breathing.repository.BreathingRepository;
import com.hsu.shimpyoo.domain.breathing.repository.DailyPefRepository;
import com.hsu.shimpyoo.domain.user.entity.User;
import com.hsu.shimpyoo.domain.user.repository.UserRepository;
import com.hsu.shimpyoo.global.response.CustomAPIResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BreathingService {
    private final BreathingRepository breathingRepository;
    private final UserRepository userRepository;
    private final DailyPefRepository dailyPefRepository;

    public CustomAPIResponse<Map<String, Object>> calculateBreathingResult(Breathing todayBreathing, User user) {
        // 하루 전날의 시작 시간과 끝 시간 계산
        LocalDateTime startOfYesterday = LocalDate.now().minusDays(1).atStartOfDay();
        LocalDateTime endOfYesterday = LocalDate.now().minusDays(1).atTime(LocalTime.MAX);

        // 하루 전의 Breathing 데이터를 userId로 조회
        Optional<Breathing> isExistBreathing = breathingRepository.findTopByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                user, startOfYesterday, endOfYesterday);

        Breathing previousBreathing;
        Double previousBreathingRate;

        // 하루 전 호흡 기록이 존재한다면
        if (isExistBreathing.isPresent()) {
            previousBreathing = isExistBreathing.get();
            previousBreathingRate = previousBreathing.getBreathingRate();
        } else {
            // 이전 호흡 데이터가 없는 경우 유저의 기본 PEF를 사용
            previousBreathingRate=user.getPef();
        }

        // 최대호기량 설정
        Double maxBreathingRate = Math.max(todayBreathing.getFirst(), Math.max(todayBreathing.getSecond(), todayBreathing.getThird()));

        // 상태 결정
        State state;
        String rateChangeDirection = "";  // 증가 또는 감소 방향
        int rateDifferencePercent = 0;


        rateDifferencePercent = (int) Math.round(((maxBreathingRate - previousBreathingRate) / previousBreathingRate) * 100);

        if (rateDifferencePercent >= 0) {
            rateChangeDirection = "증가";
        } else {
            rateChangeDirection = "감소";
            rateDifferencePercent = Math.abs(rateDifferencePercent); // 절대값으로 변환
        }

        double rateChange = (maxBreathingRate / previousBreathingRate) * 100;
        if (rateChange >= 80) {
            state = State.GOOD;
        } else if (rateChange >= 60) {
            state = State.WARNING;
        } else {
            state = State.DANGER;
        }

        // 현재 요일에 해당하는 WeekDay Enum을 가져옵니다.
        WeekDay currentWeekDay = WeekDay.valueOf(LocalDateTime.now().getDayOfWeek().name());

        // DailyPef 데이터베이스에 상태와 최대호기량을 저장
        DailyPef dailyPef = DailyPef.builder()
                .userId(user)
                .pef(maxBreathingRate)
                .state(state) // Enum 타입으로 상태 저장
                .weekDay(currentWeekDay) // Enum 타입으로 요일 저장
                .build();
        dailyPefRepository.save(dailyPef);

        // 반환 데이터
        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("status", state.getDescription()); // 한국어 설명으로 반환
        responseData.put("breathingRate", maxBreathingRate);
        responseData.put("rateDifference", rateDifferencePercent + "% " + rateChangeDirection);
        responseData.put("first", todayBreathing.getFirst());
        responseData.put("second", todayBreathing.getSecond());
        responseData.put("third", todayBreathing.getThird());

        return CustomAPIResponse.createSuccess(200, responseData, "오늘의 쉼 결과 조회에 성공했습니다.");
    }

    public List<Map<String, Object>> getWeeklyBreathingRates(User user) {
        LocalDateTime today = LocalDateTime.now();
        List<Map<String, Object>> weeklyBreathingRates = new ArrayList<>();

        for (int i = 6; i >= 0; i--) {
            LocalDateTime targetDate = today.minusDays(i);
            LocalDateTime startOfDay = targetDate.withHour(0).withMinute(0).withSecond(0).withNano(0);
            LocalDateTime endOfDay = targetDate.withHour(23).withMinute(59).withSecond(59).withNano(999999999);

            Optional<Breathing> breathingOptional = breathingRepository.findTopByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(user, startOfDay, endOfDay);

            Map<String, Object> data = new HashMap<>();
            data.put("date", targetDate.toLocalDate());
            data.put("breathingRate", breathingOptional.map(Breathing::getBreathingRate).orElse(null));
            weeklyBreathingRates.add(data);
        }

        return weeklyBreathingRates;
    }

    // 나의 최대호기량 조회 (마이페이지)
    public ResponseEntity<CustomAPIResponse<?>> getMostRecentBreathingRate(String loginId) {
        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new UsernameNotFoundException("해당 사용자가 존재하지 않습니다."));


        Breathing recentBreathing = breathingRepository.findTopByUserIdOrderByCreatedAtDesc(user);

        if (recentBreathing != null) {
            Map<String, Object> responseData = new HashMap<>();
            responseData.put("breathingRate", recentBreathing.getBreathingRate());
            return ResponseEntity.ok(CustomAPIResponse.createSuccess(200, responseData, "나의 기준 최대호기량 조회에 성공했습니다."));
        } else {
            double breathingRate = user.getPef();
            return ResponseEntity.ok(CustomAPIResponse.createSuccess(200, breathingRate, "나의 기준 최대호기량 조회에 성공했습니다."));
        }
    }

    public CustomAPIResponse<Map<String, Object>> getWeeklyBreathingAverage(User user) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate startOfWeek = now.with(DayOfWeek.MONDAY).toLocalDate();
        LocalDate endOfWeek = now.with(DayOfWeek.SUNDAY).toLocalDate();

        List<Map<String, Object>> weeklyData = new ArrayList<>();
        float totalBreathingRate = 0;
        int count = 0;

        for (LocalDate date = startOfWeek; !date.isAfter(endOfWeek); date = date.plusDays(1)) {
            WeekDay weekDay = WeekDay.valueOf(date.getDayOfWeek().name());

            // 해당 날짜의 데이터를 조회
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(23, 59, 59);
            DailyPef dailyPef = dailyPefRepository.findTopByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(user, startOfDay, endOfDay)
                    .orElse(null);

            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("weekDay", weekDay.getKoreanName());
            dayData.put("date", date.toString());
            if (dailyPef != null) {
                dayData.put("breathingRate", dailyPef.getPef());
                totalBreathingRate += dailyPef.getPef();
                count++;
            } else {
                dayData.put("breathingRate", null);
            }
            weeklyData.add(dayData);
        }

        float averageBreathingRate = count > 0 ? totalBreathingRate / count : 0;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("weeklyData", weeklyData);
        data.put("averagePef", Math.round(averageBreathingRate));

        return CustomAPIResponse.createSuccess(200, data, "주간 최대호기량 및 평균 조회에 성공했습니다.");
    }

    // 주간 평균 최대호기량 비교
    public CustomAPIResponse<Map<String, Object>> getWeeklyBreathingDifference(User user) {
        // 이번 주와 지난 주의 최대호기량 평균 계산
        double thisWeekAverage = calculateWeeklyAverage(user, LocalDate.now().with(DayOfWeek.MONDAY), LocalDate.now());
        double lastWeekAverage = calculateWeeklyAverage(user, LocalDate.now().with(DayOfWeek.MONDAY).minusWeeks(1), LocalDate.now().with(DayOfWeek.SUNDAY).minusWeeks(1));

        String state; // 증가, 유지, 감소
        int differencePercent; // 증가율

        if (lastWeekAverage == 0) {
            differencePercent = thisWeekAverage > 0 ? 100 : 0;
            state = thisWeekAverage > 0 ? "증가" : "유지";
        } else {
            differencePercent = (int) Math.abs(Math.round(((thisWeekAverage - lastWeekAverage) / lastWeekAverage) * 100));
            state = thisWeekAverage > lastWeekAverage ? "증가" : (thisWeekAverage < lastWeekAverage ? "감소" : "유지");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("lastWeekAverage", lastWeekAverage);
        data.put("thisWeekAverage", thisWeekAverage);
        data.put("differencePercent", differencePercent);
        data.put("state", state);

        return CustomAPIResponse.createSuccess(200, data, "주간 평균 최대호기량 비교 결과 조회에 성공했습니다.");
    }

    private double calculateWeeklyAverage(User user, LocalDate startOfWeek, LocalDate endOfWeek) {
        return startOfWeek.datesUntil(endOfWeek.plusDays(1))
                .map(date -> dailyPefRepository.findTopByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                                user, date.atStartOfDay(), date.atTime(LocalTime.MAX))
                        .map(DailyPef::getPef).orElse(null))
                .filter(Objects::nonNull)
                .mapToDouble(f -> f.doubleValue())
                .average()
                .orElse(0.0);
    }

    public CustomAPIResponse<Map<String, Object>> getWeeklyBreathingState(User user) {
        LocalDateTime startOfWeek = LocalDate.now().with(DayOfWeek.MONDAY).atStartOfDay();
        LocalDateTime endOfWeek = LocalDate.now().with(DayOfWeek.SUNDAY).atTime(23, 59, 59);

        List<DailyPef> weeklyPefs = dailyPefRepository.findAllByUserIdAndCreatedAtBetween(user, startOfWeek, endOfWeek);

        // 상태별로 개수 계산
        Map<State, Integer> stateCounts = new EnumMap<>(State.class);
        stateCounts.put(State.GOOD, 0);
        stateCounts.put(State.WARNING, 0);
        stateCounts.put(State.DANGER, 0);

        for (DailyPef pef : weeklyPefs) {
            State state = pef.getState();
            stateCounts.put(state, stateCounts.get(state) + 1);
        }

        String maxState;

        if (stateCounts.values().stream().allMatch(count -> count == 0)) {
            // 모든 상태의 개수가 0이면
            maxState = "이번주 측정기록이 없습니다.";
        } else {
            State highestState = State.GOOD;
            if (stateCounts.get(State.WARNING) > stateCounts.get(State.GOOD)) {
                highestState = State.WARNING;
            }
            if (stateCounts.get(State.DANGER) > stateCounts.get(highestState)) {
                highestState = State.DANGER;
            }
            maxState = highestState.getDescription();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("good", stateCounts.get(State.GOOD));
        data.put("warning", stateCounts.get(State.WARNING));
        data.put("danger", stateCounts.get(State.DANGER));
        data.put("maxState", maxState);

        return CustomAPIResponse.createSuccess(200, data, "이번 주 쉼 상태 조회에 성공했습니다.");
    }

    // 이번달 평균 최대호기량
    public CustomAPIResponse<Map<String, Object>> getMonthlyBreathingAverage(User user) {
        LocalDate now = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(now);

        // 1일부터 마지막 날까지의 범위를 지정
        LocalDate startOfMonth = currentMonth.atDay(1);
        LocalDate endOfMonth = currentMonth.atEndOfMonth();

        double[] weeklyAverages = new double[4];
        double monthlyTotal = 0;
        int weeklyCount = 0;

        // 각 주차의 평균 계산
        for (int i = 0; i < 4; i++) {
            LocalDate startOfWeek = startOfMonth.plusWeeks(i);
            LocalDate endOfWeek = startOfWeek.plusDays(6);

            if (endOfWeek.isAfter(endOfMonth)) {
                endOfWeek = endOfMonth; // 마지막 주는 월의 마지막 날까지만 계산
            }

            // 주간 평균 계산
            double weeklyAverage = calculateWeeklyAverage(user, startOfWeek, endOfWeek);
            weeklyAverages[i] = weeklyAverage;

            if (weeklyAverage > 0) {
                monthlyTotal += weeklyAverage;
                weeklyCount++;
            }
        }

        double monthlyAverage = weeklyCount > 0 ? monthlyTotal / weeklyCount : 0;

        // 결과 데이터 구성
        Map<String, Object> responseData = new LinkedHashMap<>();
        responseData.put("week1Average", weeklyAverages[0]);
        responseData.put("week2Average", weeklyAverages[1]);
        responseData.put("week3Average", weeklyAverages[2]);
        responseData.put("week4Average", weeklyAverages[3]);
        responseData.put("monthlyAverage", monthlyAverage);

        return CustomAPIResponse.createSuccess(200, responseData, "월간 평균 최대호기량 조회에 성공했습니다.");
    }
}
