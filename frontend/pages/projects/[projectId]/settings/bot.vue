<script setup lang="ts">
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'

definePageMeta({ layout: 'default' })

const { t } = useI18n()
const route = useRoute()
const apiError = useApiError()
const botStore = useBotStore()

const projectId = computed(() => String(route.params.projectId ?? ''))

const tokenInput = ref('')
const errorMessage = ref('')
const connecting = ref(false)
const disconnecting = ref(false)
const sendingTest = ref(false)
const showDisconnectModal = ref(false)

const canConnect = computed(() => tokenInput.value.trim().length > 0 && !connecting.value)

// Defensive: if the bot disappears (e.g. project switch via the store
// watcher, or concurrent disconnect from another tab) while the modal is
// open, close it so the AC19 body never renders with an empty `{username}`.
watch(
  () => botStore.current,
  (next) => {
    if (next === null) showDisconnectModal.value = false
  },
)

const maskedToken = computed(() => {
  const bot = botStore.current
  if (!bot) return ''
  // AC18 literal form: `{telegramBotId}:•••...{tokenSuffix}`. The middle
  // bullet glyph is U+2022; the only hard-coded UI string in this file.
  return `${bot.telegramBotId}:•••...${bot.tokenSuffix}`
})

onMounted(() => {
  if (!import.meta.client) return
  void botStore.fetch(projectId.value)
})

async function onConnect() {
  const token = tokenInput.value.trim()
  if (token.length === 0 || connecting.value) return
  connecting.value = true
  errorMessage.value = ''
  try {
    await botStore.connect(projectId.value, token)
  } catch (err: unknown) {
    errorMessage.value = apiError(err, 'bot.connect')
  } finally {
    // Clear the token from the local ref in BOTH success and failure paths so
    // a rejected secret does not linger in the input / Vue's reactive system
    // between retries (AC17 defense-in-depth).
    tokenInput.value = ''
    connecting.value = false
  }
}

function openDisconnectModal() {
  errorMessage.value = ''
  showDisconnectModal.value = true
}

function closeDisconnectModal() {
  if (disconnecting.value) return
  showDisconnectModal.value = false
}

async function onConfirmDisconnect() {
  if (disconnecting.value) return
  disconnecting.value = true
  errorMessage.value = ''
  try {
    await botStore.disconnect(projectId.value)
    showDisconnectModal.value = false
  } catch (err: unknown) {
    errorMessage.value = apiError(err, 'bot.disconnect')
    showDisconnectModal.value = false
  } finally {
    disconnecting.value = false
  }
}

async function onSendTest() {
  if (sendingTest.value) return
  sendingTest.value = true
  errorMessage.value = ''
  try {
    await botStore.sendTestMessage(projectId.value)
  } catch (err: unknown) {
    errorMessage.value = apiError(err, 'bot.testMessage')
  } finally {
    sendingTest.value = false
  }
}
</script>

<template>
  <div data-test="bot-page" class="max-w-2xl space-y-8">
    <SettingsSubnav :project-id="projectId" />
    <h1 class="text-2xl font-semibold">{{ t('bot.page.title') }}</h1>

    <section
      v-if="botStore.current === null"
      data-test="bot-connect-section"
      class="rounded-md border p-4 space-y-3"
    >
      <h2 class="text-lg font-medium">{{ t('bot.page.connectSection') }}</h2>
      <p class="text-sm text-gray-700">{{ t('bot.connect.form.tokenHint') }}</p>
      <div class="space-y-3">
        <label for="bot-token" class="block text-sm font-medium">
          {{ t('bot.connect.form.tokenLabel') }}
        </label>
        <input
          id="bot-token"
          v-model="tokenInput"
          data-test="bot-connect-input"
          type="password"
          autocomplete="new-password"
          spellcheck="false"
          :placeholder="t('bot.connect.form.tokenPlaceholder')"
          :disabled="connecting"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-60"
        />
        <button
          type="button"
          data-test="bot-connect-button"
          :disabled="!canConnect"
          class="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
          @click="onConnect"
        >
          {{ connecting ? t('bot.connect.form.submitting') : t('bot.connect.form.submit') }}
        </button>
      </div>
    </section>

    <section
      v-else-if="botStore.current.status === 'connected'"
      data-test="bot-connected-section"
      class="rounded-md border p-4 space-y-3"
    >
      <h2 class="text-lg font-medium">{{ t('bot.page.connectedSection') }}</h2>
      <p data-test="bot-connected-username" class="text-xl font-semibold">
        {{ `@${botStore.current.telegramUsername}` }}
      </p>
      <p data-test="bot-connected-firstname" class="text-sm text-gray-600">
        {{ botStore.current.telegramFirstName }}
      </p>
      <p class="text-sm text-gray-700">
        <span class="font-medium">{{ t('bot.connected.tokenLabel') }}:</span>
        <span data-test="bot-masked-token" class="ml-2 font-mono">{{ maskedToken }}</span>
      </p>
      <p class="text-xs text-gray-500">{{ t('bot.connected.testMessageHint') }}</p>
      <div class="flex gap-2">
        <button
          type="button"
          data-test="bot-send-test-button"
          :disabled="sendingTest"
          class="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
          @click="onSendTest"
        >
          {{ t('bot.connected.testMessage') }}
        </button>
        <button
          type="button"
          data-test="bot-disconnect-button"
          :disabled="disconnecting"
          class="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50"
          @click="openDisconnectModal"
        >
          {{ t('bot.connected.disconnect') }}
        </button>
      </div>
    </section>

    <p v-if="errorMessage" data-test="bot-error" class="text-sm text-red-600">
      {{ errorMessage }}
    </p>

    <Dialog
      :open="showDisconnectModal"
      @update:open="(v: boolean) => { if (!v) closeDisconnectModal() }"
    >
      <DialogContent data-test="bot-disconnect-modal">
        <DialogHeader>
          <DialogTitle>{{ t('bot.disconnect.modal.title') }}</DialogTitle>
          <DialogDescription>
            {{
              t('bot.disconnect.modal.body', {
                username: botStore.current?.telegramUsername ?? '',
              })
            }}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <button
            type="button"
            data-test="bot-disconnect-modal-cancel"
            :disabled="disconnecting"
            class="px-3 py-1 text-sm border rounded hover:bg-gray-50 disabled:opacity-50"
            @click="closeDisconnectModal"
          >
            {{ t('bot.disconnect.modal.cancel') }}
          </button>
          <button
            type="button"
            data-test="bot-disconnect-modal-confirm"
            :disabled="disconnecting"
            class="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50"
            @click="onConfirmDisconnect"
          >
            {{ t('bot.disconnect.modal.confirm') }}
          </button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
</template>
