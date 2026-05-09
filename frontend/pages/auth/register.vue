<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const { t } = useI18n()
const localePath = useLocalePath()
const apiError = useApiError()

const schemaComputed = computed(() =>
  toTypedSchema(
    z
      .object({
        email: z.string().email(t('validation.emailFormat')),
        password: z
          .string()
          .min(8, t('validation.passwordMin8'))
          .refine((v) => /[A-Za-z]/.test(v), t('validation.passwordHasLetter'))
          .refine((v) => /\d/.test(v), t('validation.passwordHasDigit')),
        confirmPassword: z.string(),
      })
      .refine((data) => data.password === data.confirmPassword, {
        path: ['confirmPassword'],
        message: t('validation.passwordsDoNotMatch'),
      }),
  ),
)

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: schemaComputed,
  initialValues: { email: '', password: '', confirmPassword: '' },
})

const [email, emailAttrs] = defineField('email')
const [password, passwordAttrs] = defineField('password')
const [confirmPassword, confirmPasswordAttrs] = defineField('confirmPassword')

const submitError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  submitError.value = null
  try {
    await useApi()('/api/auth/register', {
      method: 'POST',
      body: {
        email: values.email,
        password: values.password,
      },
    })
    // Backend auto-logs the user in (see AuthService.register → openSession), so we already
    // have a SESSION cookie. Pull the user into the auth store and land on the dashboard;
    // the pending-banner there carries the "verify your email" reminder.
    const authStore = useAuthStore()
    await authStore.fetchUser()
    await navigateTo(localePath('/dashboard'))
  } catch (e: unknown) {
    submitError.value = apiError(e, 'register')
  }
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">{{ t('auth.register.title') }}</h1>

    <form novalidate class="space-y-4" @submit.prevent="onSubmit">
      <div>
        <label for="email" class="block text-sm font-medium mb-1">{{ t('auth.register.emailLabel') }}</label>
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
        <label for="password" class="block text-sm font-medium mb-1">{{ t('auth.register.passwordLabel') }}</label>
        <input
          id="password"
          v-model="password"
          v-bind="passwordAttrs"
          name="password"
          type="password"
          autocomplete="new-password"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.password" data-test="password-error" class="text-sm text-red-600 mt-1">
          {{ errors.password }}
        </p>
      </div>

      <div>
        <label for="confirmPassword" class="block text-sm font-medium mb-1">{{ t('auth.register.confirmPasswordLabel') }}</label>
        <input
          id="confirmPassword"
          v-model="confirmPassword"
          v-bind="confirmPasswordAttrs"
          name="confirmPassword"
          type="password"
          autocomplete="new-password"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.confirmPassword" data-test="confirm-password-error" class="text-sm text-red-600 mt-1">
          {{ errors.confirmPassword }}
        </p>
      </div>

      <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">
        {{ submitError }}
      </p>

      <button
        type="submit"
        :disabled="isSubmitting"
        class="w-full bg-blue-600 text-white rounded-md py-2 font-medium disabled:opacity-50 hover:bg-blue-700"
      >
        {{ isSubmitting ? t('auth.register.submitting') : t('auth.register.submit') }}
      </button>
    </form>

    <p class="mt-6 text-sm text-center">
      {{ t('auth.register.haveAccount') }}
      <NuxtLinkLocale to="/auth/login" class="text-blue-600 hover:underline">{{ t('auth.register.loginLink') }}</NuxtLinkLocale>
    </p>
  </div>
</template>
