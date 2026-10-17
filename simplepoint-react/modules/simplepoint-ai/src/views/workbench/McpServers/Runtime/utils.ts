import {resolveApiErrorMessage} from '@simplepoint/shared/api/client';

export const resolveErrorMessage = (error: unknown, fallback: string) => (
  resolveApiErrorMessage(error, fallback)
);

export const formatBytes = (value?: number) => {
  if (value === undefined || value === null || !Number.isFinite(value)) return '-';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let current = value;
  let index = 0;
  while (Math.abs(current) >= 1024 && index < units.length - 1) {
    current /= 1024;
    index += 1;
  }
  const digits = current >= 10 || Number.isInteger(current) ? 0 : 1;
  return `${current.toFixed(digits)} ${units[index]}`;
};

export const formatCpu = (nanoCpus?: number) => {
  if (nanoCpus === undefined || nanoCpus === null || !Number.isFinite(nanoCpus)) return '-';
  return `${(nanoCpus / 1_000_000_000).toFixed(2)} CPU`;
};

export {formatDateTime} from '@simplepoint/shared/format/temporal';

export const statusColor = (status?: string) => {
  switch (status) {
    case 'REGISTERING':
    case 'ASSIGNED':
    case 'READY':
    case 'RUNNING':
    case 'SUCCEEDED':
      return 'green';
    case 'SCALING':
    case 'STARTING':
    case 'PENDING':
    case 'STOPPING':
      return 'blue';
    case 'IDLE':
    case 'DRAINING':
      return 'gold';
    case 'ERROR':
    case 'FAILED':
    case 'LOST':
    case 'OFFLINE':
      return 'red';
    case 'DISABLED':
    case 'CANCELLED':
      return 'default';
    default:
      return 'default';
  }
};

export const splitHosts = (value?: string) => (
  value
    ? value.split(/[\s,]+/).map((item) => item.trim()).filter(Boolean)
    : []
);
