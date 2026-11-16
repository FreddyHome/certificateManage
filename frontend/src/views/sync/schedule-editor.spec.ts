import { describe, expect, it, vi } from 'vitest';
import {
  calendarSchedule,
  defaultSchedule,
  changeFrequency,
  formatScheduleTime,
  createPreviewController,
} from './schedule-editor';
import type { SchedulePreview, SchedulePreviewState } from './schedule-editor';
import { createTask, taskPayload } from './sync-editor';

describe('业务执行计划', () => {
  it('就绪任务保存后按计划运行，草稿只保存参数，列表执行按钮保持独立', () => {
    const task = { ...createTask(), state: 'READY' as const, scheduleEnabled: false };
    const payload = taskPayload(task, {});
    expect(payload.scheduleEnabled).toBe(true);
    expect(payload.schedule).toMatchObject({ frequency: 'CALENDAR', hours: [2], minutes: [0] });
    expect(taskPayload({ ...task, state: 'DRAFT' }, {}).scheduleEnabled).toBe(false);
    expect(task.scheduleEnabled).toBe(false);
  });
  it('新计划默认每天两点，切换频率保留时区并提供对应参数', () => {
    const schedule = defaultSchedule();
    expect(schedule).toEqual({
      frequency: 'CALENDAR',
      months: [],
      monthDays: [],
      weekdays: [],
      hours: [2],
      minutes: [0],
      timezone: 'Asia/Shanghai',
    });
    expect(changeFrequency({ ...schedule, timezone: 'UTC' }, 'WEEKLY')).toEqual({
      frequency: 'WEEKLY',
      time: '02:00',
      weekdays: [1],
      timezone: 'UTC',
    });
    expect(changeFrequency(schedule, 'MONTHLY').monthDays).toEqual([1]);
    expect(changeFrequency(schedule, 'HOURLY').minuteOfHour).toBe(0);
    expect(changeFrequency(schedule, 'MINUTES').intervalMinutes).toBe(15);
  });
  it('旧常用计划转换为五列且保留原语义，自定义表达式保持原样', () => {
    expect(
      calendarSchedule({ frequency: 'WEEKLY', time: '09:30', weekdays: [1, 5], timezone: 'UTC' }),
    ).toMatchObject({
      frequency: 'CALENDAR',
      hours: [9],
      minutes: [30],
      weekdays: [1, 5],
      months: [],
      monthDays: [],
    });
    expect(
      calendarSchedule({ frequency: 'MINUTES', intervalMinutes: 15, timezone: 'UTC' }).minutes,
    ).toEqual([0, 15, 30, 45]);
    expect(
      calendarSchedule({ frequency: 'MONTHLY', time: '02:00', lastDay: true, timezone: 'UTC' }),
    ).toMatchObject({ frequency: 'CUSTOM', cron: '0 0 2 L * *' });
    const custom = { frequency: 'CUSTOM' as const, cron: '15 0 2 * * MON-FRI', timezone: 'UTC' };
    expect(calendarSchedule(custom)).toEqual(custom);
  });
  it('计划时间以计划时区显示，包含日期和星期而非浏览器本地时间', () => {
    expect(formatScheduleTime('2026-11-15T18:00:00Z', 'Asia/Shanghai')).toBe(
      '2026-11-16 周一 02:00',
    );
    expect(formatScheduleTime('2026-11-15T18:00:00Z', 'UTC')).toBe('2026-11-15 周日 18:00');
    expect(formatScheduleTime(null, 'Asia/Shanghai')).toBe('—');
  });
  it('自定义秒级计划保留秒数，避免多次触发显示成相同时间', () => {
    expect(
      formatScheduleTime('2026-11-16T02:00:10+08:00[Asia/Shanghai]', 'Asia/Shanghai', true),
    ).toBe('2026-11-16 周一 02:00:10');
    expect(
      formatScheduleTime('2026-11-16T02:00:20+08:00[Asia/Shanghai]', 'Asia/Shanghai', true),
    ).toBe('2026-11-16 周一 02:00:20');
  });
  it('旧请求与关闭定时后的响应不能覆盖当前预览；错误清空旧结果', async () => {
    vi.useFakeTimers();
    try {
      let resolveOld!: (value: SchedulePreview) => void;
      const loader = vi
        .fn()
        .mockImplementationOnce(
          () =>
            new Promise<SchedulePreview>((resolve) => {
              resolveOld = resolve;
            }),
        )
        .mockResolvedValueOnce({ summary: '新规则', nextTimes: ['new'] })
        .mockRejectedValueOnce(new Error('请选择每周执行的日期'));
      let state: SchedulePreviewState = { status: 'idle' };
      const controller = createPreviewController(loader, (next) => {
        state = next;
      });
      controller.refresh(defaultSchedule(), true);
      await vi.advanceTimersByTimeAsync(300);
      controller.refresh(changeFrequency(defaultSchedule(), 'WEEKLY'), true);
      expect(state).toEqual({ status: 'loading' });
      await vi.advanceTimersByTimeAsync(300);
      expect(state).toMatchObject({ status: 'success', preview: { summary: '新规则' } });
      resolveOld({ summary: '过期规则', nextTimes: ['old'] });
      await vi.advanceTimersByTimeAsync(0);
      expect(state).toMatchObject({ status: 'success', preview: { summary: '新规则' } });
      controller.refresh(changeFrequency(defaultSchedule(), 'WEEKLY'), true);
      await vi.advanceTimersByTimeAsync(300);
      expect(state).toEqual({ status: 'error', message: '请选择每周执行的日期' });
      controller.refresh(defaultSchedule(), true);
      controller.refresh(defaultSchedule(), false);
      await vi.advanceTimersByTimeAsync(300);
      expect(state).toEqual({ status: 'idle' });
      expect(loader).toHaveBeenCalledTimes(3);
      controller.dispose();
    } finally {
      vi.useRealTimers();
    }
  });
});
