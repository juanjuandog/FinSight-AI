import { describe, expect, it } from 'vitest';
import { ApiError } from '../src/api';

describe('ApiError', () => {
  it('preserves status, title, and message', () => {
    const err = new ApiError(429, 'Too Many Requests', 'slow down', {
      status: 429,
      title: 'Too Many Requests',
      detail: 'slow down'
    });
    expect(err.status).toBe(429);
    expect(err.title).toBe('Too Many Requests');
    expect(err.message).toBe('slow down');
    expect(err.problemDetail).toEqual({
      status: 429,
      title: 'Too Many Requests',
      detail: 'slow down'
    });
    expect(err.name).toBe('ApiError');
  });

  it('is a real Error so it can be caught with `instanceof Error`', () => {
    const err = new ApiError(500, 'Server Error', 'oops');
    expect(err).toBeInstanceOf(Error);
    expect(err.stack).toBeDefined();
  });
});
