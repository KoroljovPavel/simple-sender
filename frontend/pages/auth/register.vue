<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'auth' })

const schema = z
  .object({
    email: z.string().email('Введіть коректний email'),
    name: z.string().trim().min(1, 'Ім\'я обов\'язкове'),
    password: z
      .string()
      .min(8, 'Мін. 8 символів')
      .refine((v) => /[A-Za-z]/.test(v), 'Має містити хоча б одну літеру')
      .refine((v) => /\d/.test(v), 'Має містити хоча б одну цифру'),
    confirmPassword: z.string(),
  })
  .refine((data) => data.password === data.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Паролі не співпадають',
  })

const { defineField, handleSubmit, isSubmitting, errors } = useForm({
  validationSchema: toTypedSchema(schema),
  initialValues: { email: '', name: '', password: '', confirmPassword: '' },
})

const [email, emailAttrs] = defineField('email')
const [name, nameAttrs] = defineField('name')
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
        name: values.name,
        password: values.password,
      },
    })
    await navigateTo({ path: '/auth/login', query: { registered: '1' } })
  } catch (e: unknown) {
    const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.status ?? status?.response?.status
    if (code === 409) {
      submitError.value = 'Користувач з таким email вже існує. Якщо це ваш акаунт, зверніться до підтримки.'
    } else if (code === 400) {
      submitError.value = 'Перевірте правильність даних і спробуйте ще раз'
    } else if (code === 429) {
      submitError.value = 'Забагато спроб реєстрації. Спробуйте через хвилину'
    } else {
      submitError.value = 'Не вдалося завершити реєстрацію. Спробуйте пізніше'
    }
  }
})
</script>

<template>
  <div>
    <h1 class="text-2xl font-semibold text-center mb-6">Реєстрація</h1>

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
        <label for="name" class="block text-sm font-medium mb-1">Ім'я</label>
        <input
          id="name"
          v-model="name"
          v-bind="nameAttrs"
          name="name"
          type="text"
          autocomplete="name"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.name" data-test="name-error" class="text-sm text-red-600 mt-1">
          {{ errors.name }}
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
          autocomplete="new-password"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="errors.password" data-test="password-error" class="text-sm text-red-600 mt-1">
          {{ errors.password }}
        </p>
      </div>

      <div>
        <label for="confirmPassword" class="block text-sm font-medium mb-1">Підтвердження пароля</label>
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
        {{ isSubmitting ? 'Зачекайте…' : 'Зареєструватись' }}
      </button>
    </form>

    <p class="mt-6 text-sm text-center">
      Вже маєте акаунт?
      <NuxtLink to="/auth/login" class="text-blue-600 hover:underline">Увійти</NuxtLink>
    </p>
  </div>
</template>
