package com.certmanage.sync;

import static com.certmanage.common.Errors.require;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.scheduling.support.CronExpression;

/**
 * @description 统一编译业务执行计划、中文摘要和触发时间，旧规则仅在可无损识别时转换
 * @author Freddy
 * @date 2026-11-16
 */
public final class SchedulePlan {
  private static final String DEFAULT_ZONE = "Asia/Shanghai";
  private static final List<String> WEEK = List.of("周一", "周二", "周三", "周四", "周五", "周六", "周日");

  /**
   * @description 阻止无状态计划工具实例化
   * @author Freddy
   * @date 2026-11-16
   */
  private SchedulePlan() {}

  /**
   * @description 保存同一规范化结果派生的业务参数、表达式、时区和摘要
   * @author Freddy
   * @date 2026-11-16
   * @param plan 规范化业务参数
   * @param cron 调度器输入
   * @param timezone 有效时区
   * @param summary 中文规则摘要
   */
  public record Resolved(Map<String, Object> plan, String cron, String timezone, String summary) {
    /**
     * @description 从给定时刻计算未来三次触发时间，按计划时区处理夏令时
     * @author Freddy
     * @date 2026-11-16
     * @param after 预览起点，不包含该时刻
     * @return 包含时区和偏移量的三次计划时间
     * @throws IllegalArgumentException 没有未来触发时间
     */
    public List<String> nextTimes(Instant after) {
      var expression = CronExpression.parse(cron);
      var cursor = after.atZone(ZoneId.of(timezone));
      var result = new ArrayList<String>();
      for (int i = 0; i < 3; i++) {
        cursor = expression.next(cursor);
        require(cursor != null, "该执行计划没有未来执行时间");
        result.add(cursor.toString());
      }
      return result;
    }

    /**
     * @description 生成只读预览响应，不保存任务或触发同步
     * @author Freddy
     * @date 2026-11-16
     * @return 当前时间起的计划摘要及最近三次时间
     */
    public Map<String, Object> preview() {
      return Map.of(
          "schedule",
          plan,
          "cron",
          cron,
          "timezone",
          timezone,
          "summary",
          summary,
          "nextTimes",
          nextTimes(Instant.now()));
    }
  }

  /**
   * @description 严格校验并编译业务计划，丢弃不属于当前频率的冗余字段
   * @author Freddy
   * @date 2026-11-16
   * @param input 业务表单参数；自定义模式包含原始cron
   * @return 同一来源生成的计划及调度字段
   * @throws IllegalArgumentException 日期、频率、表达式或时区不合法
   */
  public static Resolved normalize(Map<String, ?> input) {
    String frequency = Objects.toString(input.get("frequency"), "");
    String zone = Objects.toString(input.get("timezone"), DEFAULT_ZONE);
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException("请选择有效的执行时区");
    }
    var plan = new LinkedHashMap<String, Object>();
    plan.put("frequency", frequency);
    plan.put("timezone", zone);
    String cron, summary;
    if (frequency.equals("CALENDAR")) {
      var months = calendarValues(input.get("months"), 1, 12, "月份请选择1–12月");
      var dates = calendarValues(input.get("monthDays"), 1, 31, "日期请选择1–31日");
      var week = calendarValues(input.get("weekdays"), 1, 7, "请选择有效星期");
      var hours = calendarValues(input.get("hours"), 0, 23, "小时请选择0–23");
      var minutes = calendarValues(input.get("minutes"), 0, 59, "分钟请选择0–59");
      plan.put("months", months);
      plan.put("monthDays", dates);
      plan.put("weekdays", week);
      plan.put("hours", hours);
      plan.put("minutes", minutes);
      cron =
          "0 "
              + calendarField(minutes, 0, 59)
              + " "
              + calendarField(hours, 0, 23)
              + " "
              + calendarField(dates, 1, 31)
              + " "
              + calendarField(months, 1, 12)
              + " "
              + calendarField(week, 1, 7);
      if (months.isEmpty()
          && dates.isEmpty()
          && week.isEmpty()
          && hours.size() == 1
          && minutes.size() == 1)
        summary =
            String.format(Locale.ROOT, "每天 %02d:%02d 执行", hours.getFirst(), minutes.getFirst());
      else
        summary =
            (months.isEmpty() ? "每月" : join(months).replace(",", "、") + "月")
                + " · "
                + (dates.isEmpty() ? "每天" : join(dates).replace(",", "、") + "日")
                + " · "
                + (week.isEmpty()
                    ? "不限星期"
                    : week.stream().map(d -> WEEK.get(d - 1)).collect(Collectors.joining("、")))
                + " · "
                + (hours.isEmpty() ? "每小时" : join(hours).replace(",", "、") + "时")
                + " · "
                + (minutes.isEmpty() ? "每分钟" : join(minutes).replace(",", "、") + "分");
    } else if (frequency.equals("CUSTOM")) {
      cron = Objects.toString(input.get("cron"), "");
      require(cron.length() <= 500, "自定义执行规则不得超过500字");
      try {
        CronExpression.parse(cron);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("自定义执行规则无效，请检查高级设置");
      }
      plan.put("cron", cron);
      summary = "自定义计划";
    } else if (frequency.equals("HOURLY")) {
      int minute = number(input.get("minuteOfHour"), 0, 59, "请选择每小时的执行分钟（0–59）");
      plan.put("minuteOfHour", minute);
      cron = "0 " + minute + " * * * *";
      summary = "每小时第 " + minute + " 分钟执行";
    } else if (frequency.equals("MINUTES")) {
      int interval = number(input.get("intervalMinutes"), 1, 30, "请选择分钟间隔");
      require(Set.of(5, 10, 15, 20, 30).contains(interval), "分钟间隔仅支持5、10、15、20、30分钟");
      plan.put("intervalMinutes", interval);
      cron = "0 */" + interval + " * * * *";
      summary = "每 " + interval + " 分钟执行（按时钟对齐）";
    } else {
      require(Set.of("DAILY", "WEEKLY", "MONTHLY").contains(frequency), "请选择执行频率");
      String time = Objects.toString(input.get("time"), "");
      require(time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"), "请选择有效的执行时间");
      plan.put("time", time);
      int hour = Integer.parseInt(time.substring(0, 2)),
          minute = Integer.parseInt(time.substring(3));
      String prefix = "0 " + minute + " " + hour + " ";
      if (frequency.equals("DAILY")) {
        cron = prefix + "* * *";
        summary = "每天 " + time + " 执行";
      } else if (frequency.equals("WEEKLY")) {
        var days = numbers(input.get("weekdays"), 1, 7, "请选择每周执行的日期");
        plan.put("weekdays", days);
        cron = prefix + "* * " + join(days);
        summary =
            "每"
                + days.stream().map(d -> WEEK.get(d - 1)).collect(Collectors.joining("、"))
                + " "
                + time
                + " 执行";
      } else {
        boolean last = Boolean.TRUE.equals(input.get("lastDay"));
        plan.put("lastDay", last);
        if (last) {
          require(
              input.get("monthDays") == null
                  || input.get("monthDays") instanceof List<?> days && days.isEmpty(),
              "请选择月内日期或最后一天，不能同时选择");
          cron = prefix + "L * *";
          summary = "每月最后一天 " + time + " 执行";
        } else {
          var days = numbers(input.get("monthDays"), 1, 28, "请选择每月执行日期（1–28日）");
          plan.put("monthDays", days);
          cron = prefix + join(days) + " * *";
          summary =
              "每月 "
                  + days.stream().map(d -> d + " 日").collect(Collectors.joining("、"))
                  + " "
                  + time
                  + " 执行";
        }
      }
    }
    return new Resolved(
        Collections.unmodifiableMap(plan), cron, zone, summary + " · " + zoneLabel(zone));
  }

  /**
   * @description 仅识别明确支持的旧规则；不支持的组合保留原表达式，禁止猜测转换
   * @author Freddy
   * @date 2026-11-16
   * @param cron 旧调度表达式
   * @param zone 原时区
   * @return 可编辑的常用计划或原样自定义计划
   */
  public static Resolved legacy(String cron, String zone) {
    var custom = normalize(Map.of("frequency", "CUSTOM", "cron", cron, "timezone", zone));
    String[] f = cron.trim().split("\\s+");
    if (f.length != 6 || !f[0].equals("0") || !f[4].equals("*")) return custom;
    var p = new LinkedHashMap<String, Object>();
    p.put("timezone", zone);
    try {
      if (f[3].equals("*")
          && f[5].equals("*")
          && f[2].equals("*")
          && f[1].matches("\\*/(?:5|10|15|20|30)")) {
        p.put("frequency", "MINUTES");
        p.put("intervalMinutes", Integer.parseInt(f[1].substring(2)));
      } else if (f[1].matches("[0-9]{1,2}")
          && f[2].equals("*")
          && f[3].equals("*")
          && f[5].equals("*")) {
        p.put("frequency", "HOURLY");
        p.put("minuteOfHour", Integer.parseInt(f[1]));
      } else if (f[1].matches("[0-9]{1,2}") && f[2].matches("[0-9]{1,2}")) {
        p.put(
            "time",
            String.format(
                Locale.ROOT, "%02d:%02d", Integer.parseInt(f[2]), Integer.parseInt(f[1])));
        if (f[3].equals("*") && f[5].equals("*")) p.put("frequency", "DAILY");
        else if (f[3].equals("*") && f[5].matches("[1-7](?:,[1-7])*")) {
          p.put("frequency", "WEEKLY");
          p.put("weekdays", Arrays.stream(f[5].split(",")).map(Integer::valueOf).toList());
        } else if (f[5].equals("*") && f[3].equals("L")) {
          p.put("frequency", "MONTHLY");
          p.put("lastDay", true);
        } else if (f[5].equals("*") && f[3].matches("[0-9]{1,2}(?:,[0-9]{1,2})*")) {
          p.put("frequency", "MONTHLY");
          p.put("monthDays", Arrays.stream(f[3].split(",")).map(Integer::valueOf).toList());
        } else return custom;
      } else return custom;
      // 识别仅用于回显，仍保留原cron，避免无关编辑顺便改写原规则。
      var result = normalize(p);
      return new Resolved(result.plan(), cron, zone, result.summary());
    } catch (IllegalArgumentException e) {
      return custom;
    }
  }

  /**
   * @description 校验整数，拒绝字符串、分数和越界值
   * @author Freddy
   * @date 2026-11-16
   * @param value 输入值
   * @param min 最小值
   * @param max 最大值
   * @param message 业务错误提示
   * @return 合法整数
   * @throws IllegalArgumentException 输入不合法
   */
  private static int number(Object value, int min, int max, String message) {
    require(value instanceof Number, message);
    double n = ((Number) value).doubleValue();
    require(Double.isFinite(n) && n >= min && n <= max && n == Math.rint(n), message);
    return (int) n;
  }

  /**
   * @description 校验至少一个日期并去重排序
   * @author Freddy
   * @date 2026-11-16
   * @param value 日期列表
   * @param min 最小日期
   * @param max 最大日期
   * @param message 错误提示
   * @return 有序日期
   * @throws IllegalArgumentException 为空或越界
   */
  private static List<Integer> numbers(Object value, int min, int max, String message) {
    require(value instanceof List<?> list && !list.isEmpty() && list.size() <= 100, message);
    return ((List<?>) value)
        .stream().map(v -> number(v, min, max, message)).distinct().sorted().toList();
  }

  /**
   * @description 将日期列表转为调度字段
   * @author Freddy
   * @date 2026-11-16
   * @param values 有序日期
   * @return 逗号分隔的数字
   */
  private static String join(List<Integer> values) {
    return values.stream().map(Object::toString).collect(Collectors.joining(","));
  }

  /**
   * @description 校验五列选择，空或全选统一表示不限，避免无意义的完整枚举
   * @author Freddy
   * @date 2026-11-16
   * @param value 用户多选值，缺省表示不限
   * @param min 最小值
   * @param max 最大值
   * @param message 越界提示
   * @return 去重排序后的值，空表示不限
   * @throws IllegalArgumentException 格式或数值不合法
   */
  private static List<Integer> calendarValues(Object value, int min, int max, String message) {
    if (value == null || value instanceof List<?> list && list.isEmpty()) return List.of();
    var values = numbers(value, min, max, message);
    return values.size() == max - min + 1 ? List.of() : values;
  }

  /**
   * @description 将选择值压缩为调度字段，等距完整序列使用步长，其他值保留精确枚举
   * @author Freddy
   * @date 2026-11-16
   * @param values 有序选择，空表示不限
   * @param min 字段最小值
   * @param max 字段最大值
   * @return 不改变匹配集合的表达式字段
   */
  private static String calendarField(List<Integer> values, int min, int max) {
    if (values.isEmpty()) return "*";
    if (min == 0 && values.size() > 2 && values.getFirst() == min) {
      int step = values.get(1) - min;
      if (step > 1
          && values.size() == (max - min) / step + 1
          && java.util.stream.IntStream.range(0, values.size())
              .allMatch(i -> values.get(i) == min + i * step)) return "*/" + step;
    }
    return join(values);
  }

  /**
   * @description 将默认时区转换成业务名称，其余保留地区标识便于准确区分
   * @author Freddy
   * @date 2026-11-16
   * @param zone 已校验的时区
   * @return 时区名称
   */
  private static String zoneLabel(String zone) {
    return DEFAULT_ZONE.equals(zone) ? "北京时间" : zone;
  }
}
