<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const schema = z.object({
  email: z.string().email('Введіть коректний email'),
  password: z.string().min(8, 'Пароль має містити щонайменше 8 символів'),
  rememberMe: z.boolean(),
})

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: toTypedSchema(schema),
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
    await navigateTo('/dashboard')
  } catch (e: unknown) {
    const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.status ?? status?.response?.status
    if (code === 429) {
      submitError.value = 'Забагато спроб. Спробуйте через 15 хв'
    } else if (code === 401) {
      submitError.value = 'Невірний email або пароль'
    } else if (code === 403) {
      submitError.value = 'Доступ заборонено. Зверніться до підтримки'
    } else {
      submitError.value = 'Не вдалося увійти. Спробуйте пізніше'
    }
  }
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">Увійти</h1>

    <form novalidate class="space-y-4" @submit.prevent="onSubmit">
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

      <div>
        <label for="password" class="block text-sm font-medium mb-1">Пароль</label>
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
        Запам'ятати мене
      </label>

      <p v-if="submitError" data-test="submit-error" class="text-sm text-red-600">
        {{ submitError }}
      </p>

      <button
        type="submit"
        :disabled="isSubmitting"
        class="w-full bg-blue-600 text-white rounded-md py-2 font-medium disabled:opacity-50 hover:bg-blue-700"
      >
        {{ isSubmitting ? 'Зачекайте…' : 'Увійти' }}
      </button>
    </form>

    <div class="mt-6 flex justify-between text-sm">
      <NuxtLink to="/auth/register" class="text-blue-600 hover:underline">Зареєструватись</NuxtLink>
      <NuxtLink to="/auth/forgot-password" class="text-blue-600 hover:underline">Забули пароль?</NuxtLink>
    </div>
  </div>
</template>
