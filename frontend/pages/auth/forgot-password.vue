<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const schema = z.object({
  email: z.string().email('Введіть коректний email'),
})

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: toTypedSchema(schema),
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
    <h1 class="text-2xl font-semibold text-center mb-6">Відновлення пароля</h1>

    <div v-if="submitted" data-test="success-message" class="space-y-4">
      <p class="text-sm text-gray-700">
        Якщо акаунт з таким email існує, ви отримаєте лист з інструкціями для відновлення пароля.
      </p>
      <NuxtLink
        to="/auth/login"
        class="block w-full text-center bg-blue-600 text-white rounded-md py-2 font-medium hover:bg-blue-700"
      >
        Повернутись до входу
      </NuxtLink>
    </div>

    <form v-else novalidate class="space-y-4" @submit.prevent="onSubmit">
      <p class="text-sm text-gray-600">
        Вкажіть email, на який ви реєструвалися. Ми надішлемо посилання для скидання пароля.
      </p>

      <div>
        <label for="email" class="block text-sm font-medium mb-1">Email</label>
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
        {{ isSubmitting ? 'Надсилаємо…' : 'Надіслати лист' }}
      </button>

      <p class="text-sm text-center">
        <NuxtLink to="/auth/login" class="text-blue-600 hover:underline">Повернутись до входу</NuxtLink>
      </p>
    </form>
  </div>
</template>
