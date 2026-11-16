package com.certmanage.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * @description 验证业务计划编译、旧规则兼容及跨月时区的实际触发时间
 * @author Freddy
 * @date 2026-11-16
 */
class SchedulePlanTest {
  /**
   * @description 星期选择保持精确集合，不因调度器包含星期0而改变周末触发日期
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void weekdaySelectionsDoNotShiftWhenEvenlySpaced() {
    var plan =
        SchedulePlan.normalize(
            Map.of(
                "frequency",
                "CALENDAR",
                "weekdays",
                List.of(1, 3, 5, 7),
                "hours",
                List.of(2),
                "minutes",
                List.of(0)));
    var times = plan.nextTimes(Instant.parse("2026-11-20T16:01:00Z"));
    assertEquals("2026-11-22T02:00+08:00[Asia/Shanghai]", times.get(0));
    assertEquals("2026-11-23T02:00+08:00[Asia/Shanghai]", times.get(1));
  }

  /**
   * @description 五列计划按交集匹配，并压缩长数字列表以适应已有调度字段长度
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void compilesCalendarSelectionsAndIntersectsDateWithWeekday() {
    var plan =
        SchedulePlan.normalize(
            Map.of(
                "frequency",
                "CALENDAR",
                "months",
                List.of(1, 3),
                "monthDays",
                List.of(1, 15),
                "weekdays",
                List.of(1),
                "hours",
                List.of(2, 14),
                "minutes",
                List.of(0, 30)));
    assertEquals("0 0,30 2,14 1,15 1,3 1", plan.cron());
    assertEquals(
        "2027-03-01T02:00+08:00[Asia/Shanghai]",
        plan.nextTimes(Instant.parse("2026-12-31T00:00:00Z")).get(0));
    var defaults =
        SchedulePlan.normalize(
            Map.of("frequency", "CALENDAR", "hours", List.of(2), "minutes", List.of(0)));
    assertEquals("0 0 2 * * *", defaults.cron());
    var dense =
        SchedulePlan.normalize(
            Map.of(
                "frequency",
                "CALENDAR",
                "months",
                java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList(),
                "monthDays",
                java.util.stream.IntStream.rangeClosed(1, 31).boxed().toList(),
                "hours",
                java.util.stream.IntStream.range(0, 24).boxed().toList(),
                "minutes",
                java.util.stream.IntStream.range(0, 60).filter(i -> i % 2 == 0).boxed().toList()));
    assertTrue(dense.cron().length() <= 100);
    assertEquals("0 */2 * * * *", dense.cron());
    assertThrows(
        IllegalArgumentException.class,
        () -> SchedulePlan.normalize(Map.of("frequency", "CALENDAR", "months", List.of(13))));
    assertThrows(
        IllegalArgumentException.class,
        () -> SchedulePlan.normalize(Map.of("frequency", "CALENDAR", "minutes", List.of(-1))));
  }

  /**
   * @description 五种频率生成明确规则且服务端摘要与规范化参数一致
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void compilesBusinessFrequencies() {
    assertEquals(
        "0 0 2 * * *",
        SchedulePlan.normalize(Map.of("frequency", "DAILY", "time", "02:00")).cron());
    var weekly =
        SchedulePlan.normalize(
            Map.of("frequency", "WEEKLY", "time", "09:30", "weekdays", List.of(5, 1, 1)));
    assertEquals("0 30 9 * * 1,5", weekly.cron());
    assertEquals("每周一、周五 09:30 执行 · 北京时间", weekly.summary());
    assertEquals(
        "0 0 2 1,15 * *",
        SchedulePlan.normalize(
                Map.of("frequency", "MONTHLY", "time", "02:00", "monthDays", List.of(15, 1)))
            .cron());
    assertEquals(
        "0 0 2 L * *",
        SchedulePlan.normalize(Map.of("frequency", "MONTHLY", "time", "02:00", "lastDay", true))
            .cron());
    assertEquals(
        "0 10 * * * *",
        SchedulePlan.normalize(Map.of("frequency", "HOURLY", "minuteOfHour", 10)).cron());
    assertEquals(
        "0 */15 * * * *",
        SchedulePlan.normalize(Map.of("frequency", "MINUTES", "intervalMinutes", 15)).cron());
  }

  /**
   * @description 校验缺少日期、非法数值、歧义月份及无效时区，不能静默降级
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void rejectsInvalidPlans() {
    for (var plan :
        List.of(
            Map.of("frequency", "WEEKLY", "time", "02:00", "weekdays", List.of()),
            Map.of("frequency", "MONTHLY", "time", "02:00", "monthDays", List.of(31)),
            Map.of(
                "frequency", "MONTHLY", "time", "02:00", "lastDay", true, "monthDays", List.of(1)),
            Map.of("frequency", "HOURLY", "minuteOfHour", 1.5),
            Map.of("frequency", "MINUTES", "intervalMinutes", 7),
            Map.of("frequency", "DAILY", "time", "25:00"),
            Map.of("frequency", "DAILY", "time", "02:00", "timezone", "bad/zone"))) {
      assertThrows(IllegalArgumentException.class, () -> SchedulePlan.normalize(plan));
    }
  }

  /**
   * @description 常见旧规则可无损回显，复杂规则及秒级触发保持原文
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void preservesLegacyRules() {
    assertEquals(
        "DAILY", SchedulePlan.legacy("0 0 2 * * *", "Asia/Shanghai").plan().get("frequency"));
    assertEquals(
        "WEEKLY", SchedulePlan.legacy("0 30 9 * * 1,5", "Asia/Shanghai").plan().get("frequency"));
    for (String cron :
        List.of("15 0 2 * * *", "0 0 2 31 * *", "0 0 2 * * MON-FRI", "0 0 2 1 * MON")) {
      var legacy = SchedulePlan.legacy(cron, "Asia/Shanghai");
      assertEquals("CUSTOM", legacy.plan().get("frequency"));
      assertEquals(cron, legacy.cron());
      assertEquals(cron, SchedulePlan.normalize(legacy.plan()).cron());
    }
  }

  /**
   * @description 真实调度器计算闰年月末和时钟对齐间隔，不依赖保存时刻
   * @author Freddy
   * @date 2026-11-16
   */
  @Test
  void previewsLeapMonthAndAlignedMinutes() {
    var month =
        SchedulePlan.normalize(Map.of("frequency", "MONTHLY", "time", "02:00", "lastDay", true));
    var times = month.nextTimes(Instant.parse("2028-02-01T00:00:00Z"));
    assertEquals("2028-02-29T02:00+08:00[Asia/Shanghai]", times.get(0));
    var minute = SchedulePlan.normalize(Map.of("frequency", "MINUTES", "intervalMinutes", 15));
    assertEquals(
        "2026-12-31T23:15+08:00[Asia/Shanghai]",
        minute.nextTimes(Instant.parse("2026-12-31T15:07:00Z")).get(0));
    var daily =
        SchedulePlan.normalize(
            Map.of("frequency", "DAILY", "time", "09:00", "timezone", "America/New_York"));
    assertEquals(
        "2026-03-07T09:00-05:00[America/New_York]",
        daily.nextTimes(Instant.parse("2026-03-07T00:00:00Z")).get(0));
    assertEquals(
        "2026-03-08T09:00-04:00[America/New_York]",
        daily.nextTimes(Instant.parse("2026-03-07T00:00:00Z")).get(1));
  }
}
