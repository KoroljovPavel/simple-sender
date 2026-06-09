import { describe, it, expect } from 'vitest'
import type {
  BlockType,
  ContentBlock,
  MediaItem,
  PreviewStepRequest,
  PreviewStepResponse,
  RenderedBlock,
} from '../../types/funnel'

// Compile-time assignability fixture (15-message-composer / Task 6). This file has NO runtime logic to
// test — the value is that it FAILS TO COMPILE if types/funnel.ts drifts from the backend DTOs
// (ContentBlockDto / MediaItemDto / PreviewStepRequest / PreviewStepResponse). The trivial expect() only
// keeps vitest from treating the file as empty. See the task TDD Anchor.

// One sample ContentBlock per BlockType — mirrors the per-type field population of ContentBlockDto.
const textBlock: ContentBlock = { type: 'TEXT', text: 'hi {{first_name}}', parseMode: 'HTML' }
const imageBlock: ContentBlock = { type: 'IMAGE', mediaUrl: 'https://x/i.png', caption: 'pic' }
const videoBlock: ContentBlock = { type: 'VIDEO', mediaUrl: 'https://x/v.mp4' }
const audioBlock: ContentBlock = { type: 'AUDIO', mediaUrl: 'tg-file-id' }
const fileBlock: ContentBlock = { type: 'FILE', mediaUrl: 'https://x/d.pdf', caption: 'doc' }

const albumItems: MediaItem[] = [
  { type: 'IMAGE', mediaUrl: 'https://x/1.png', caption: 'first only' },
  { type: 'VIDEO', mediaUrl: 'https://x/2.mp4' },
]
const albumBlock: ContentBlock = { type: 'ALBUM', items: albumItems }

const allBlocks: ContentBlock[] = [textBlock, imageBlock, videoBlock, audioBlock, fileBlock, albumBlock]

// Exhaustiveness: every BlockType member is represented above.
const blockKinds: BlockType[] = allBlocks.map((b) => b.type)

const request: PreviewStepRequest = { stepType: 'MESSAGE', blocks: allBlocks }

const renderedTextBlock: RenderedBlock = { type: 'TEXT', text: 'hi Ivan', parseMode: 'HTML' }
const renderedAlbumBlock: RenderedBlock = {
  type: 'ALBUM',
  items: [{ mediaUrl: 'https://x/1.png', caption: 'first only' }],
}
const response: PreviewStepResponse = {
  renderedBlocks: [renderedTextBlock, renderedAlbumBlock],
  sampleData: false,
  kind: 'message',
}

describe('funnel block types (compile-time mirror of backend DTOs)', () => {
  it('constructs every BlockType and the multiblock preview DTOs without TS errors', () => {
    expect(blockKinds).toHaveLength(6)
    expect(request.blocks).toHaveLength(6)
    expect(response.renderedBlocks).toHaveLength(2)
  })
})
