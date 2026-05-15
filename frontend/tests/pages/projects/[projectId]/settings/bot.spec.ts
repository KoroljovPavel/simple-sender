import { describe, it, expect, beforeEach, vi } from 'vitest'
import { reactive } from 'vue'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { config } from '@vue/test-utils'
import { settle } from '../../../../helpers/settle'
import type { Bot } from '../../../../../types/bot'

// Render Teleport content inline so wrapper.find() can see the modal portaled
// out of the page subtree by reka-ui's DialogPortal. vue-test-utils' built-in
// `teleport: true` stub drops the slot (seen empirically — element renders but
// slot is unmounted). An explicit template stub renders the slot inline so
// modal data-test attributes are reachable via wrapper.find().
config.global.stubs = {
  ...config.global.stubs,
  teleport: { template: '<div data-test-teleport><slot /></div>' },
}

const PROJECT_ID = 'p1'
const TOKEN_REGEX = /\d{1,20}:[A-Za-z0-9_-]{30,50}/

const BOT_FIXTURE: Bot = {
  telegramBotId: 1234567890,
  telegramUsername: 'SmokeBot',
  telegramFirstName: 'Smoke',
  tokenSuffix: 'xyz',
  status: 'connected',
  connectedAt: '2026-05-15T12:00:00Z',
}

const VALID_TOKEN = '1234567890:AAFakeTokenABCDEFGHIJKLMNOPQRSTUVWXYZ'

const { botStoreMock, routeMock } = vi.hoisted(() => ({
  botStoreMock: {
    current: null as Bot | null,
    fetch: vi.fn(),
    connect: vi.fn(),
    disconnect: vi.fn(),
    sendTestMessage: vi.fn(),
  },
  routeMock: {
    params: { projectId: 'p1' } as Record<string, string>,
    path: '/projects/p1/settings/bot',
  },
}))

// reactive() proxies the same target — mutations via this proxy trigger Vue
// reactivity for any template/computed binding inside the mounted page.
const reactiveStore = reactive(botStoreMock) as typeof botStoreMock

mockNuxtImport('useBotStore', () => () => reactiveStore)
mockNuxtImport('useRoute', () => () => routeMock)
mockNuxtImport('useLocalePath', () => () => (p: string) => p)
mockNuxtImport('useApi', () => () => vi.fn())

import BotPage from '../../../../../pages/projects/[projectId]/settings/bot.vue'

function resetMock() {
  botStoreMock.fetch.mockReset()
  botStoreMock.connect.mockReset()
  botStoreMock.disconnect.mockReset()
  botStoreMock.sendTestMessage.mockReset()
  botStoreMock.fetch.mockResolvedValue(null)
  reactiveStore.current = null
  routeMock.params = { projectId: PROJECT_ID }
}

describe('projects/[projectId]/settings/bot page', () => {
  beforeEach(() => {
    resetMock()
  })

  it('mounts and fetches bot on mount', async () => {
    await mountSuspended(BotPage)
    await settle()

    expect(botStoreMock.fetch).toHaveBeenCalledTimes(1)
    expect(botStoreMock.fetch).toHaveBeenCalledWith(PROJECT_ID)
  })

  it('renders Connect form when bot.current is null', async () => {
    const wrapper = await mountSuspended(BotPage)
    await settle()

    expect(wrapper.find('[data-test="bot-connect-input"]').exists()).toBe(true)
    const button = wrapper.find('[data-test="bot-connect-button"]').element as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })

  it('renders SettingsSubnav with both routes (AC21 integration)', async () => {
    const wrapper = await mountSuspended(BotPage)
    await settle()

    expect(wrapper.find('[data-test="settings-subnav"]').exists()).toBe(true)
    const general = wrapper.find('[data-test="settings-subnav-general"]')
    const bot = wrapper.find('[data-test="settings-subnav-bot"]')
    expect(general.attributes('href')).toBe(`/projects/${PROJECT_ID}/settings`)
    expect(bot.attributes('href')).toBe(`/projects/${PROJECT_ID}/settings/bot`)
  })

  it('Connect button enables once token is typed', async () => {
    const wrapper = await mountSuspended(BotPage)
    await settle()

    const input = wrapper.find('[data-test="bot-connect-input"]')
    const button = () => wrapper.find('[data-test="bot-connect-button"]').element as HTMLButtonElement

    expect(button().disabled).toBe(true)
    await input.setValue('any-non-empty')
    await settle()
    expect(button().disabled).toBe(false)
  })

  it('Connect button stays disabled when input is whitespace only', async () => {
    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue('   ')
    await settle()
    const button = wrapper.find('[data-test="bot-connect-button"]').element as HTMLButtonElement
    expect(button.disabled).toBe(true)
  })

  it('loading state disables input and button during connect', async () => {
    let resolveConnect!: (b: Bot) => void
    botStoreMock.connect.mockReturnValueOnce(
      new Promise<Bot>((res) => {
        resolveConnect = res
      }),
    )

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    const input = wrapper.find('[data-test="bot-connect-input"]').element as HTMLInputElement
    const button = wrapper.find('[data-test="bot-connect-button"]').element as HTMLButtonElement
    expect(input.disabled).toBe(true)
    expect(button.disabled).toBe(true)

    resolveConnect(BOT_FIXTURE)
    reactiveStore.current = BOT_FIXTURE
    await settle()
  })

  it('successful connect swaps to Connected view', async () => {
    botStoreMock.connect.mockImplementationOnce(async () => {
      reactiveStore.current = BOT_FIXTURE
      return BOT_FIXTURE
    })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-connected-username"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="bot-connected-username"]').text()).toContain('@SmokeBot')
    expect(wrapper.find('[data-test="bot-connected-firstname"]').text()).toContain('Smoke')
    expect(wrapper.find('[data-test="bot-send-test-button"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="bot-disconnect-button"]').exists()).toBe(true)
  })

  it('masked token uses literal AC18 form', async () => {
    reactiveStore.current = BOT_FIXTURE

    const wrapper = await mountSuspended(BotPage)
    await settle()

    expect(wrapper.find('[data-test="bot-masked-token"]').text()).toBe('1234567890:•••...xyz')
  })

  it('connect 400 resolves errors.bot.connect.400', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 400 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    const err = wrapper.find('[data-test="bot-error"]')
    expect(err.exists()).toBe(true)
    expect(err.text()).toMatch(/формат/i)
  })

  it('connect 409 resolves errors.bot.connect.409', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 409 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/іншого проєкту/i)
  })

  it('connect 422 resolves errors.bot.connect.422', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 422 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/невалідний/i)
  })

  it('connect 429 resolves errors.bot.connect.429', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 429 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/15 хвилин/i)
  })

  it('connect 500 resolves errors.bot.connect.500', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 500 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/вебхука/i)
  })

  it('connect 502 resolves errors.bot.connect.502', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 502 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/недоступний/i)
  })

  it('Disconnect click opens modal with AC19 copy', async () => {
    reactiveStore.current = BOT_FIXTURE

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-disconnect-button"]').trigger('click')
    await settle()

    const modal = wrapper.find('[data-test="bot-disconnect-modal"]')
    expect(modal.exists()).toBe(true)
    // AC19 exact copy with {username} interpolated.
    expect(modal.text()).toContain('@SmokeBot')
    expect(modal.text()).toMatch(/Вхідні повідомлення припиняться/i)
  })

  it('Cancel inside modal closes without API call', async () => {
    reactiveStore.current = BOT_FIXTURE

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-disconnect-button"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="bot-disconnect-modal-cancel"]').trigger('click')
    await settle()

    expect(botStoreMock.disconnect).not.toHaveBeenCalled()
    // The Dialog component stays mounted; only its inner content unmounts when
    // closed. Asserting on the confirm button — which lives inside the
    // unmountable subtree — proves the modal closed without a network call.
    expect(wrapper.find('[data-test="bot-disconnect-modal-confirm"]').exists()).toBe(false)
  })

  it('Confirm inside modal calls disconnect and swaps to Connect form', async () => {
    reactiveStore.current = BOT_FIXTURE
    botStoreMock.disconnect.mockImplementationOnce(async () => {
      reactiveStore.current = null
    })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-disconnect-button"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="bot-disconnect-modal-confirm"]').trigger('click')
    await settle()

    expect(botStoreMock.disconnect).toHaveBeenCalledTimes(1)
    expect(botStoreMock.disconnect).toHaveBeenCalledWith(PROJECT_ID)
    expect(wrapper.find('[data-test="bot-connect-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="bot-connected-username"]').exists()).toBe(false)
  })

  it('disconnect 404 resolves errors.bot.disconnect.404', async () => {
    reactiveStore.current = BOT_FIXTURE
    botStoreMock.disconnect.mockRejectedValueOnce({ statusCode: 404 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-disconnect-button"]').trigger('click')
    await settle()
    await wrapper.find('[data-test="bot-disconnect-modal-confirm"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/підключеного бота/i)
  })

  it('test message 422 resolves errors.bot.testMessage.422', async () => {
    reactiveStore.current = BOT_FIXTURE
    botStoreMock.sendTestMessage.mockRejectedValueOnce({ statusCode: 422 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-send-test-button"]').trigger('click')
    await settle()

    expect(wrapper.find('[data-test="bot-error"]').text()).toMatch(/\/start/i)
  })

  it('token never appears in error message or DOM (AC17)', async () => {
    botStoreMock.connect.mockRejectedValueOnce({ statusCode: 422 })

    const wrapper = await mountSuspended(BotPage)
    await settle()

    await wrapper.find('[data-test="bot-connect-input"]').setValue(VALID_TOKEN)
    await settle()
    await wrapper.find('[data-test="bot-connect-button"]').trigger('click')
    await settle()

    const dom = wrapper.text()
    const errText = wrapper.find('[data-test="bot-error"]').text()
    expect(dom).not.toMatch(TOKEN_REGEX)
    expect(errText).not.toMatch(TOKEN_REGEX)
  })

  it('renders Connected view from initial fetch (no Connect interaction)', async () => {
    reactiveStore.current = BOT_FIXTURE

    const wrapper = await mountSuspended(BotPage)
    await settle()

    expect(wrapper.find('[data-test="bot-connected-username"]').text()).toContain('@SmokeBot')
    expect(wrapper.find('[data-test="bot-masked-token"]').text()).toBe('1234567890:•••...xyz')
    expect(wrapper.find('[data-test="bot-connect-input"]').exists()).toBe(false)
  })
})
