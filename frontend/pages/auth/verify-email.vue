<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

type State = 'loading' | 'success' | 'invalid' | 'expired' | 'no-token'

const { t } = useI18n()

const route = useRoute()
const token = computed(() => {
  const rawToken = route.query.token
  return typeof rawToken === 'string' && rawToken.length > 0 ? rawToken : null
})

const state = ref<State>(token.value ? 'loading' : 'no-token')

async function verify() {
  if (!token.value) {
    state.value = 'no-token'
    return
  }
  state.value = 'loading'
  try {
    await useApi()(`/api/auth/verify-email`, {
      method: 'GET',
      query: { token: token.value },
    })
    state.value = 'success'
  } catch (e: unknown) {
    const err = e as {
      statusCode?: number
      status?: number
      data?: { code?: string }
      response?: { status?: number; _data?: { code?: string } }
    }
    const code = err?.statusCode ?? err?.status ?? err?.response?.status
    const errorCode = err?.data?.code ?? err?.response?._data?.code
    if (code === 400 && errorCode === 'TOKEN_EXPIRED') {
      state.value = 'expired'
    } else {
      state.value = 'invalid'
    }
  }
}

onMounted(() => {
  void verify()
})

const resendSchemaComputed = computed(() =>
  toTypedSchema(
    z.object({
      email: z.string().email(t('validation.emailFormat')),
    }),
  ),
)

const {
  defineField: defineResendField,
  handleSubmit: handleResend,
  isSubmitting: isResending,
  errors: resendErrors,
} = useForm({
  validationSchema: resendSchemaComputed,
  initialValues: { email: '' },
})

const [resendEmail, resendEmailAttrs] = defineResendField('email')

const resendStatus = ref<'idle' | 'sent' | 'cooldown' | 'error'>('idle')
const cooldownSecondsLeft = ref(0)
let cooldownTimer: ReturnType<typeof setInterval> | null = null

function startCooldown(seconds: number) {
  cooldownSecondsLeft.value = seconds
  if (cooldownTimer) clearInterval(cooldownTimer)
  cooldownTimer = setInterval(() => {
    cooldownSecondsLeft.value -= 1
    if (cooldownSecondsLeft.value <= 0 && cooldownTimer) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
      if (resendStatus.value === 'cooldown') resendStatus.value = 'idle'
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (cooldownTimer) clearInterval(cooldownTimer)
})

const onResend = handleResend(async (values) => {
  if (cooldownSecondsLeft.value > 0) return
  try {
    await useApi()('/api/auth/resend-verification', {
      method: 'POST',
      body: { email: values.email },
    })
    resendStatus.value = 'sent'
    startCooldown(60)
  } catch (e: unknown) {
    const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.status ?? status?.response?.status
    if (code === 429) {
      resendStatus.value = 'cooldown'
      startCooldown(60)
    } else {
      resendStatus.value = 'error'
    }
  }
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">{{ t('auth.verifyEmail.title') }}</h1>

    <div v-if="state === 'loading'" data-test="loading" class="text-center py-8">
      <div class="inline-block w-8 h-8 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
      <p class="text-sm text-gray-600 mt-3">{{ t('auth.verifyEmail.loading') }}</p>
    </div>

    <div v-else-if="state === 'success'" data-test="success" class="space-y-4 text-center">
      <p class="text-sm text-gray-700">{{ t('auth.verifyEmail.successMessage') }}</p>
      <NuxtLinkLocale
        to="/auth/login"
        class="block w-full bg-blue-600 text-white rounded-md py-2 font-medium hover:bg-blue-700"
      >
        {{ t('auth.verifyEmail.loginLink') }}
      </NuxtLinkLocale>
    </div>

    <div v-else-if="state === 'invalid' || state === 'no-token'" data-test="invalid" class="space-y-4 text-center">
      <p class="text-sm text-gray-700">{{ t('auth.verifyEmail.invalidMessage') }}</p>
      <NuxtLinkLocale
        to="/auth/login"
        class="block w-full bg-blue-600 text-white rounded-md py-2 font-medium hover:bg-blue-700"
      >
        {{ t('auth.verifyEmail.backToLogin') }}
      </NuxtLinkLocale>
    </div>

    <div v-else-if="state === 'expired'" data-test="expired" class="space-y-4">
      <p class="text-sm text-gray-700 text-center">{{ t('auth.verifyEmail.expiredMessage') }}</p>

      <form novalidate class="space-y-3" @submit.prevent="onResend">
        <p class="text-xs text-gray-600">
          {{ t('auth.verifyEmail.resendHint') }}
        </p>
        <div>
          <label for="resend-email" class="block text-sm font-medium mb-1">{{ t('auth.verifyEmail.emailLabel') }}</label>
          <input
            id="resend-email"
            v-model="resendEmail"
            v-bind="resendEmailAttrs"
            name="email"
            type="email"
            autocomplete="email"
            class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="resendErrors.email" data-test="resend-email-error" class="text-sm text-red-600 mt-1">
            {{ resendErrors.email }}
          </p>
        </div>

        <p v-if="resendStatus === 'sent'" data-test="resend-sent" class="text-sm text-green-700">
          {{ t('auth.verifyEmail.resendSent') }}
        </p>
        <p v-if="resendStatus === 'cooldown'" data-test="resend-cooldown" class="text-sm text-gray-600">
          {{ t('auth.verifyEmail.cooldown', { seconds: cooldownSecondsLeft }) }}
        </p>
        <p v-if="resendStatus === 'error'" data-test="resend-error" class="text-sm text-red-600">
          {{ t('auth.verifyEmail.resendError') }}
        </p>

        <button
          type="submit"
          data-test="resend-button"
          :disabled="isResending || cooldownSecondsLeft > 0"
          class="w-full bg-blue-600 text-white rounded-md py-2 font-medium disabled:opacity-50 hover:bg-blue-700"
        >
          {{ cooldownSecondsLeft > 0 ? t('auth.verifyEmail.resendCooldown', { seconds: cooldownSecondsLeft }) : t('auth.verifyEmail.resendSubmit') }}
        </button>
      </form>
    </div>
  </div>
</template>
