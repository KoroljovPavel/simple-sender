<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const { t } = useI18n()
const localePath = useLocalePath()
const apiError = useApiError()

const route = useRoute()
const token = computed(() => {
  const raw = route.query.token
  return typeof raw === 'string' && raw.length > 0 ? raw : null
})

const schemaComputed = computed(() =>
  toTypedSchema(
    z
      .object({
        newPassword: z
          .string()
          .min(8, t('validation.passwordMin8'))
          .refine((v) => /[A-Za-z]/.test(v), t('validation.passwordHasLetter'))
          .refine((v) => /\d/.test(v), t('validation.passwordHasDigit')),
        confirmPassword: z.string(),
      })
      .refine((data) => data.newPassword === data.confirmPassword, {
        path: ['confirmPassword'],
        message: t('validation.passwordsDoNotMatch'),
      }),
  ),
)

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: schemaComputed,
  initialValues: { newPassword: '', confirmPassword: '' },
})

const [newPassword, newPasswordAttrs] = defineField('newPassword')
const [confirmPassword, confirmPasswordAttrs] = defineField('confirmPassword')

const linkInvalid = ref(false)
const submitError = ref<string | null>(null)

const onSubmit = handleSubmit(async (values) => {
  submitError.value = null
  if (!token.value) {
    linkInvalid.value = true
    return
  }
  try {
    await useApi()('/api/auth/reset-password', {
      method: 'POST',
      body: { token: token.value, newPassword: values.newPassword },
    })
    await navigateTo({ path: localePath('/auth/login'), query: { reset: '1' } })
  } catch (e: unknown) {
    const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.status ?? status?.response?.status
    if (code === 400) {
      linkInvalid.value = true
    } else {
      submitError.value = apiError(e, 'resetPassword')
    }
  }
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">{{ t('auth.resetPassword.title') }}</h1>

    <div v-if="!token || linkInvalid" data-test="invalid-link" class="space-y-4">
      <p class="text-sm text-gray-700">
        {{ t('auth.resetPassword.linkInvalidOrExpired') }}
      </p>
      <NuxtLinkLocale
        to="/auth/forgot-password"
        class="block w-full text-center bg-blue-600 text-white rounded-md py-2 font-medium hover:bg-blue-700"
      >
        {{ t('auth.resetPassword.requestNewLink') }}
      </NuxtLinkLocale>
    </div>

    <form v-else novalidate class="space-y-4" @submit.prevent="onSubmit">
      <div>
        <label for="newPassword" class="block text-sm font-medium mb-1">{{ t('auth.resetPassword.newPasswordLabel') }}</label>
        <input
          id="newPassword"
          v-model="newPassword"
          v-bind="newPasswordAttrs"
          name="newPassword"
          type="password"
          autocomplete="new-password"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.newPassword" data-test="new-password-error" class="text-sm text-red-600 mt-1">
          {{ errors.newPassword }}
        </p>
      </div>

      <div>
        <label for="confirmPassword" class="block text-sm font-medium mb-1">{{ t('auth.resetPassword.confirmPasswordLabel') }}</label>
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
        {{ isSubmitting ? t('auth.resetPassword.submitting') : t('auth.resetPassword.submit') }}
      </button>
    </form>
  </div>
</template>
