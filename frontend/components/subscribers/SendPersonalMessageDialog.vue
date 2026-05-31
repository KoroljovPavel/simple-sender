<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'
import { toast } from 'vue-sonner'
import {
  Dialog,
  DialogScrollContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '~/components/ui/dialog'
import type { SendMessageResponse } from '~/types/subscriber'

const props = defineProps<{ open: boolean; projectId: string; subscriberId: string }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; refresh: [] }>()

const { t } = useI18n()
const resolveError = useApiError()

const MAX = 4096

// Schema wrapped in computed() so a live locale switch re-runs validation in the new language
// (patterns.md:145). Submit is also gated by form validity + a character counter.
const schema = computed(() =>
  toTypedSchema(
    z.object({
      text: z
        .string()
        .trim()
        .min(1, t('validation.messageRequired'))
        .max(MAX, t('validation.messageMax', { max: MAX })),
    }),
  ),
)

const { handleSubmit, defineField, resetForm, meta } = useForm({
  validationSchema: schema,
  initialValues: { text: '' },
})
const [text, textAttrs] = defineField('text')

const submitting = ref(false)
const count = computed(() => (text.value ?? '').length)
const overCap = computed(() => count.value > MAX)

const onSubmit = handleSubmit(async (values) => {
  submitting.value = true
  try {
    // Mass-assignment defense: the body carries EXACTLY {text} — no owner-bound field is ever echoed.
    const resp = await useApi()<SendMessageResponse>(
      `/api/v1/projects/${props.projectId}/subscribers/${props.subscriberId}/messages`,
      { method: 'POST', body: { text: values.text } },
    )
    if (resp.status === 'sent') {
      toast.success(t('subscribers.profile.sendMessage.success'))
    } else {
      // blocked / deleted: Telegram is the source of truth — the backend already flipped the status,
      // so refresh the profile to flip the badge, and warn (server message; no seeded i18n key).
      toast.warning(resp.message)
      emit('refresh')
    }
    resetForm()
    emit('update:open', false)
  } catch (err) {
    // 429 personal_message_rate_limited / 503 telegram_rate_limited / any other → resolver walks to
    // errors.generic (no per-code key seeded by Task 2).
    toast.error(resolveError(err, 'subscribers.sendMessage') || t('errors.generic'))
  } finally {
    submitting.value = false
  }
})
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent>
      <DialogHeader>
        <DialogTitle>{{ t('subscribers.profile.sendMessage.dialog.title') }}</DialogTitle>
        <DialogDescription class="sr-only">
          {{ t('subscribers.profile.sendMessage.dialog.textLabel') }}
        </DialogDescription>
      </DialogHeader>

      <form data-test="send-personal-message-form" class="space-y-3" @submit.prevent="onSubmit">
        <div>
          <label for="send-personal-message-text" class="block text-sm font-medium mb-1">
            {{ t('subscribers.profile.sendMessage.dialog.textLabel') }}
          </label>
          <textarea
            id="send-personal-message-text"
            v-model="text"
            v-bind="textAttrs"
            data-test="send-personal-message-text"
            rows="5"
            class="w-full rounded-md border px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p :class="['mt-1 text-right text-xs', overCap ? 'text-red-600' : 'text-gray-400']">
            {{ count }}/{{ MAX }}
          </p>
        </div>

        <DialogFooter>
          <button
            type="button"
            data-test="send-personal-message-cancel"
            class="rounded-md border px-3 py-1.5 text-sm hover:bg-gray-50"
            @click="emit('update:open', false)"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="submit"
            data-test="send-personal-message-submit"
            :disabled="submitting || !meta.valid"
            class="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {{ t('subscribers.profile.sendMessage.dialog.submit') }}
          </button>
        </DialogFooter>
      </form>
    </DialogScrollContent>
  </Dialog>
</template>
