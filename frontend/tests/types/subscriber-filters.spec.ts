import { describe, it, expect } from 'vitest'
import type { SegmentFilter } from '../../types/subscriber'
import { defaultFilter, toListQuery, toExportFilter } from '../../types/subscriber'

const full: SegmentFilter = {
  search: 'ivan',
  status: 'active',
  tagsInclude: ['vip'],
  tagsExclude: ['cold'],
  subscribedFrom: '2026-01-01T00:00:00Z',
  subscribedTo: '2026-02-01T23:59:59Z',
  sort: 'last_seen_desc',
}

describe('toListQuery', () => {
  it('omits everything but limit+sort for the default filter', () => {
    expect(toListQuery(defaultFilter(), { limit: 50 })).toEqual({ limit: 50, sort: 'created_desc' })
  })

  it('includes status, tag arrays, dates, and search when present', () => {
    expect(toListQuery(full, { limit: 50 })).toEqual({
      limit: 50,
      sort: 'last_seen_desc',
      search: 'ivan',
      status: 'active',
      tagsInclude: ['vip'],
      tagsExclude: ['cold'],
      subscribedFrom: '2026-01-01T00:00:00Z',
      subscribedTo: '2026-02-01T23:59:59Z',
    })
  })

  it('drops a <2-char search (never trips backend @Size(min=2))', () => {
    const q = toListQuery({ ...defaultFilter(), search: 'a' }, { limit: 50 })
    expect(q).not.toHaveProperty('search')
  })

  it('passes cursor through only when provided', () => {
    expect(toListQuery(defaultFilter(), { limit: 50, cursor: 'c1' })).toMatchObject({ cursor: 'c1' })
    expect(toListQuery(defaultFilter(), { limit: 50 })).not.toHaveProperty('cursor')
  })
})

describe('toExportFilter', () => {
  it('maps status/sort to Java enum NAMES and keeps arrays/dates', () => {
    expect(toExportFilter(full)).toEqual({
      search: 'ivan',
      status: 'ACTIVE',
      tagsInclude: ['vip'],
      tagsExclude: ['cold'],
      subscribedFrom: '2026-01-01T00:00:00Z',
      subscribedTo: '2026-02-01T23:59:59Z',
      sort: 'LAST_SEEN_DESC',
      cursor: null,
      limit: 0,
    })
  })

  it('emits null status/search and CREATED_DESC for an empty default filter', () => {
    expect(toExportFilter(defaultFilter())).toMatchObject({
      status: null,
      search: null,
      sort: 'CREATED_DESC',
      cursor: null,
      limit: 0,
    })
  })

  it('drops a <2-char search to null', () => {
    expect(toExportFilter({ ...defaultFilter(), search: 'a' }).search).toBeNull()
  })
})
