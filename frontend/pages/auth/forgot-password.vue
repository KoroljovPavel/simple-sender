<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const { t } = useI18n()

const schemaComputed = computed(() =>
  toTypedSchema(
    z.object({
      email: z.string().email(t('validation.emailFormat')),
    }),
  ),
)

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: schemaComputed,
  initialValues: { email: '' },
})

const [email, emailAttrs] = defineField('email')

const submitted = ref(false)

const onSubmit = handleSubmit(async (values) => {
  try {
    await useApi()('/api/auth/forgot-password', {
      method: 'POST',
      body: { email: values.email },
    })
  } catch {
    // Always present a successful state to avoid revealing whether the email exists.
  }
  submitted.value = true
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">{{ t('auth.forgotPassword.title') }}</h1>

    <div v-if="submitted" data-test="success-message" class="space-y-4">
      <p class="text-sm text-gray-700">
        {{ t('auth.forgotPassword.successMessage') }}
      </p>
      <NuxtLinkLocale
        to="/auth/login"
        class="block w-full text-center bg-blue-600 text-white rounded-md py-2 font-medium hover:bg-blue-700"
      >
        {{ t('auth.forgotPassword.backToLogin') }}
      </NuxtLinkLocale>
    </div>

    <form v-else novalidate class="space-y-4" @submit.prevent="onSubmit">
      <p class="text-sm text-gray-600">
        {{ t('auth.forgotPassword.hint') }}
      </p>

      <div>
        <label for="email" class="block text-sm font-medium mb-1">{{ t('auth.forgotPassword.emailLabel') }}</label>
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

      <button
        type="submit"
        :disabled="isSubmitting"
        class="w-full bg-blue-600 text-white rounded-md py-2 font-medium disabled:opacity-50 hover:bg-blue-700"
      >
        {{ isSubmitting ? t('auth.forgotPassword.submitting') : t('auth.forgotPassword.submit') }}
      </button>

      <p class="text-sm text-center">
        <NuxtLinkLocale to="/auth/login" class="text-blue-600 hover:underline">{{ t('auth.forgotPassword.backToLogin') }}</NuxtLinkLocale>
      </p>
    </form>
  </div>
</template>
