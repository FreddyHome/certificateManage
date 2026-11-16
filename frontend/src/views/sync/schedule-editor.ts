import type {
  ExecutionSchedule,
  ScheduleFrequency,
  SchedulePreview,
} from '../../services/sync-types';
export type { SchedulePreview } from '../../services/sync-types';
export type SchedulePreviewState =
  | { status: 'idle' | 'loading' }
  | { status: 'success'; preview: SchedulePreview }
  | { status: 'error'; message: string };

export function defaultSchedule(): ExecutionSchedule {
  return {
    frequency: 'CALENDAR',
    months: [],
    monthDays: [],
    weekdays: [],
    hours: [2],
    minutes: [0],
    timezone: 'Asia/Shanghai',
  };
}

/** 旧常用计划只做无损转换，月底和复杂规则继续保留原表达式。 */
export function calendarSchedule(schedule: ExecutionSchedule): ExecutionSchedule {
  if (schedule.frequency === 'CUSTOM' || schedule.frequency === 'CALENDAR')
    return JSON.parse(JSON.stringify(schedule));
  const [hour, minute] = (schedule.time || '02:00').split(':').map(Number);
  const result: ExecutionSchedule = {
    ...defaultSchedule(),
    timezone: schedule.timezone,
    hours: [hour!],
    minutes: [minute!],
  };
  if (schedule.frequency === 'WEEKLY') result.weekdays = [...(schedule.weekdays || [])];
  if (schedule.frequency === 'MONTHLY') {
    if (schedule.lastDay)
      return {
        frequency: 'CUSTOM',
        timezone: schedule.timezone,
        cron: `0 ${minute} ${hour} L * *`,
      };
    result.monthDays = [...(schedule.monthDays || [])];
  }
  if (schedule.frequency === 'HOURLY') {
    result.hours = [];
    result.minutes = [schedule.minuteOfHour ?? 0];
  }
  if (schedule.frequency === 'MINUTES') {
    result.hours = [];
    result.minutes = Array.from({ length: 60 }, (_, i) => i).filter(
      (i) => i % (schedule.intervalMinutes || 15) === 0,
    );
  }
  return result;
}

export function changeFrequency(
  schedule: ExecutionSchedule,
  frequency: ScheduleFrequency,
): ExecutionSchedule {
  const common = { frequency, timezone: schedule.timezone };
  if (frequency === 'HOURLY') return { ...common, minuteOfHour: 0 };
  if (frequency === 'MINUTES') return { ...common, intervalMinutes: 15 };
  return {
    ...common,
    time: schedule.time || '02:00',
    ...(frequency === 'WEEKLY' ? { weekdays: [1] } : {}),
    ...(frequency === 'MONTHLY' ? { monthDays: [1], lastDay: false } : {}),
  };
}

/** 所有计划时间按计划时区显示，与当前浏览器时区无关。 */
export function formatScheduleTime(
  value: string | null | undefined,
  zone: string,
  showSeconds = false,
): string {
  if (!value) return '—';
  try {
    const parts = new Intl.DateTimeFormat('zh-CN', {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      weekday: 'short',
      hour: '2-digit',
      minute: '2-digit',
      ...(showSeconds ? { second: '2-digit' as const } : {}),
      hourCycle: 'h23',
    }).formatToParts(new Date(value.replace(/\[.*\]$/, '')));
    const get = (type: Intl.DateTimeFormatPartTypes) =>
      parts.find((p) => p.type === type)?.value || '';
    return `${get('year')}-${get('month')}-${get('day')} ${get('weekday')} ${get('hour')}:${get('minute')}${showSeconds ? ':' + get('second') : ''}`;
  } catch {
    return '时间无效';
  }
}

/** 参数变更立即作废旧预览；请求仅防抖计算，不保存任务。 */
export function createPreviewController(
  loader: (schedule: ExecutionSchedule) => Promise<SchedulePreview>,
  publish: (state: SchedulePreviewState) => void,
) {
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  return {
    refresh(schedule: ExecutionSchedule, enabled: boolean) {
      const current = ++generation;
      clearTimeout(timer);
      publish({ status: enabled ? 'loading' : 'idle' });
      if (!enabled) return;
      const snapshot: ExecutionSchedule = JSON.parse(JSON.stringify(schedule));
      timer = setTimeout(async () => {
        try {
          const preview = await loader(snapshot);
          if (generation === current) publish({ status: 'success', preview });
        } catch (error) {
          if (generation === current)
            publish({
              status: 'error',
              message: error instanceof Error ? error.message : '计划时间预览失败，请重试',
            });
        }
      }, 300);
    },
    dispose() {
      ++generation;
      clearTimeout(timer);
    },
  };
}
