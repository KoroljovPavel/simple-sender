<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const { t } = useI18n()
const localePath = useLocalePath()
const apiError = useApiError()
const route = useRoute()

// One-shot info banner driven by query flags set by sibling auth flows:
//   ?registered=1 — coming from successful /auth/register (verify-email letter dispatched)
//   ?reset=1     — coming from successful /auth/reset-password (all sessions invalidated)
// Computed (not stored): if the user submits login and the URL is rewritten,
// the banner naturally goes away — no manual dismiss needed.
const infoBanner = computed<string | null>(() => {
  if (route.query.registered === '1') return t('auth.login.registeredBanner')
  if (route.query.reset === '1') return t('auth.login.resetBanner')
  return null
})

const schemaComputed = computed(() =>
  toTypedSchema(
    z.object({
      email: z.string().email(t('validation.emailFormat')),
      password: z.string().min(8, t('validation.passwordMin8Login')),
      rememberMe: z.boolean(),
    }),
  ),
)

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: schemaComputed,
  initialValues: { email: '', password: '', rememberMe: false },
})

const [email, emailAttrs] = defineField('email')
const [password, passwordAttrs] = defineField('password')
const [rememberMe, rememberMeAttrs] = defineField('rememberMe')

const submitError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  submitError.value = null
  try {
    await useApi()('/api/auth/login', {
      method: 'POST',
      body: {
        email: values.email,
        password: values.password,
        rememberMe: values.rememberMe,
      },
    })
    const authStore = useAuthStore()
    await authStore.fetchUser()
    await navigateTo(localePath('/dashboard'))
  } catch (e: unknown) {
    submitError.value = apiError(e, 'login')
  }
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">{{ t('auth.login.title') }}</h1>

    <p
      v-if="infoBanner"
      data-test="info-banner"
      class="mb-4 rounded-md border border-emerald-300 bg-emerald-50 px-4 py-3 text-sm text-emerald-900"
    >
      {{ infoBanner }}
    </p>

    <form novalidate class="space-y-4" @submit.prevent="onSubmit">
      <div>
        <label for="email" class="block text-sm font-medium mb-1">{{ t('auth.login.emailLabel') }}</label>
        <input
          id="email"
          v-model="email"
          v-bind="emailAttrs"
          name="email"
          type="email"
          autocomplete="email"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.email" data-test="email-error" class="text-sm text-red-600 mt-1">
          {{ errors.email }}
        </p>
      </div>

      <div>
        <label for="password" class="block text-sm font-medium mb-1">{{ t('auth.login.passwordLabel') }}</label>
        <input
          id="password"
          v-model="password"
          v-bind="passwordAttrs"
          name="password"
          type="password"
          autocomplete="current-password"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.password" data-test="password-error" class="text-sm text-red-600 mt-1">
          {{ errors.password }}
        </p>
      </div>

      <label class="flex items-center gap-2 text-sm">
        <input v-model="rememberMe" v-bind="rememberMeAttrs" type="checkbox" class="rounded border" />
        {{ t('auth.login.rememberMe') }}
      </label>

      <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">
        {{ submitError }}
      </p>

      <button
        type="submit"
        :disabled="isSubmitting"
        class="w-full bg-blue-600 text-white rounded-md py-2 font-medium disabled:opacity-50 hover:bg-blue-700"
      >
        {{ isSubmitting ? t('auth.login.submitting') : t('auth.login.submit') }}
      </button>
    </form>

    <div class="mt-6 flex justify-between text-sm">
      <NuxtLinkLocale to="/auth/register" class="text-blue-600 hover:underline">{{ t('auth.login.registerLink') }}</NuxtLinkLocale>
      <NuxtLinkLocale to="/auth/forgot-password" class="text-blue-600 hover:underline">{{ t('auth.login.forgotPasswordLink') }}</NuxtLinkLocale>
    </div>
  </div>
</template>
