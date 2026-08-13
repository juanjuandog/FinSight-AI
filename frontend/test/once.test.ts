import { describe, expect, it, vi } from 'vitest';
import { once } from '../src/api';

describe('once', () => {
  it('deduplicates concurrent calls sharing a key', async () => {
    const fn = vi.fn().mockResolvedValue('value');
    const [a, b] = await Promise.all([once('k', fn), once('k', fn)]);
    expect(fn).toHaveBeenCalledTimes(1);
    expect(a).toBe('value');
    expect(b).toBe('value');
  });

  it('does not deduplicate across different keys', async () => {
    const fn = vi.fn().mockResolvedValue('value');
    await Promise.all([once('k1', fn), once('k2', fn)]);
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('clears the slot once the promise resolves', async () => {
    const fn = vi.fn().mockResolvedValueOnce('first').mockResolvedValueOnce('second');
    const first = await once('k', fn);
    const second = await once('k', fn);
    expect(first).toBe('first');
    expect(second).toBe('second');
    expect(fn).toHaveBeenCalledTimes(2);
  });

  it('clears the slot when the underlying promise rejects', async () => {
    const fn = vi
      .fn()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce('ok');
    await expect(once('k', fn)).rejects.toThrow('boom');
    await expect(once('k', fn)).resolves.toBe('ok');
  });
});
