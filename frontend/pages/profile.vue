<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { z } from 'zod'

definePageMeta({ layout: 'default' })

const authStore = useAuthStore()

// ─── Section 1: Profile info (view/edit name) ───────────────────────────
const isEditingName = ref(false)
const draftName = ref(authStore.user?.name ?? '')
const nameError = ref<string | null>(null)
const nameStatus = ref<'idle' | 'saving' | 'saved'>('idle')

watch(
  () => authStore.user?.name,
  (next) => {
    if (!isEditingName.value) draftName.value = next ?? ''
  },
)

function startNameEdit() {
  draftName.value = authStore.user?.name ?? ''
  nameError.value = null
  nameStatus.value = 'idle'
  isEditingName.value = true
}

function cancelNameEdit() {
  isEditingName.value = false
  draftName.value = authStore.user?.name ?? ''
  nameError.value = null
}

async function saveName() {
  const trimmed = draftName.value.trim()
  if (trimmed.length === 0) {
    nameError.value = 'Ім\'я обов\'язкове'
    return
  }
  if (trimmed.length > 100) {
    nameError.value = 'Ім\'я не може бути довшим за 100 символів'
    return
  }
  nameError.value = null
  nameStatus.value = 'saving'
  try {
    await useApi()('/api/profile', {
      method: 'PATCH',
      body: { name: trimmed },
    })
    if (authStore.user) authStore.user.name = trimmed
    nameStatus.value = 'saved'
    isEditingName.value = false
  } catch {
    nameError.value = 'Не вдалося зберегти. Спробуйте пізніше'
    nameStatus.value = 'idle'
  }
}

// ─── Section 2: Resend verification (pending only) ──────────────────────
const resendStatus = ref<'idle' | 'sent' | 'cooldown' | 'error'>('idle')
const resendCooldown = ref(0)
let resendTimer: ReturnType<typeof setInterval> | null = null

function startResendCooldown(seconds: number) {
  resendCooldown.value = seconds
  if (resendTimer) clearInterval(resendTimer)
  resendTimer = setInterval(() => {
    resendCooldown.value -= 1
    if (resendCooldown.value <= 0 && resendTimer) {
      clearInterval(resendTimer)
      resendTimer = null
      if (resendStatus.value === 'cooldown') resendStatus.value = 'idle'
    }
  }, 1000)
}

onBeforeUnmount(() => {
  if (resendTimer) clearInterval(resendTimer)
})

async function onResend() {
  if (resendCooldown.value > 0) return
  try {
    await useApi()('/api/auth/resend-verification', {
      method: 'POST',
      body: { email: authStore.user?.email ?? '' },
    })
    resendStatus.value = 'sent'
    startResendCooldown(60)
  } catch (e: unknown) {
    const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.status ?? status?.response?.status
    if (code === 429) {
      resendStatus.value = 'cooldown'
      startResendCooldown(60)
    } else {
      resendStatus.value = 'error'
    }
  }
}

// ─── Section 3: Change password ─────────────────────────────────────────
const changePwdSchema = z
  .object({
    currentPassword: z.string().min(1, 'Введіть поточний пароль'),
    newPassword: z
      .string()
      .min(8, 'Мін. 8 символів')
      .refine((v) => /[A-Za-z]/.test(v), 'Має містити хоча б одну літеру')
      .refine((v) => /\d/.test(v), 'Має містити хоча б одну цифру'),
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Паролі не співпадають',
  })

const {
  defineField: defineCpField,
  handleSubmit: handleChangePwd,
  isSubmitting: isChangingPwd,
  errors: changePwdErrors,
  resetForm: resetChangePwdForm,
} = useForm({
  validationSchema: toTypedSchema(changePwdSchema),
  initialValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
})

const [currentPassword, currentPasswordAttrs] = defineCpField('currentPassword')
const [newPassword, newPasswordAttrs] = defineCpField('newPassword')
const [confirmPassword, confirmPasswordAttrs] = defineCpField('confirmPassword')

const changePwdStatus = ref<'idle' | 'success' | 'error'>('idle')
const changePwdError = ref<string | null>(null)

const onChangePassword = handleChangePwd(async (values) => {
  changePwdStatus.value = 'idle'
  changePwdError.value = null
  try {
    await useApi()('/api/profile/change-password', {
      method: 'POST',
      body: {
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      },
    })
    changePwdStatus.value = 'success'
    resetChangePwdForm()
  } catch (e: unknown) {
    const status = (e as { statusCode?: number; status?: number; response?: { status?: number } })
    const code = status?.statusCode ?? status?.status ?? status?.response?.status
    changePwdStatus.value = 'error'
    if (code === 400) {
      changePwdError.value = 'Невірний поточний пароль'
    } else if (code === 429) {
      changePwdError.value = 'Забагато спроб. Спробуйте через 15 хв'
    } else {
      changePwdError.value = 'Не вдалося змінити пароль. Спробуйте пізніше'
    }
  }
})

// ─── Section 4: Terminate all sessions ──────────────────────────────────
const isTerminating = ref(false)
const terminateError = ref<string | null>(null)

async function onTerminateAllSessions() {
  isTerminating.value = true
  terminateError.value = null
  try {
    await useApi()('/api/profile/terminate-all-sessions', { method: 'POST' })
    // Clear store before navigating so the auth middleware does not trigger an
    // extra GET /api/auth/me (the session is already gone server-side).
    authStore.user = null
    await navigateTo('/auth/login')
  } catch {
    isTerminating.value = false
    terminateError.value = 'Не вдалося завершити сесії. Спробуйте пізніше'
  }
}

// ─── Section 5: Delete account ──────────────────────────────────────────
const isDeleteModalOpen = ref(false)
const isDeleting = ref(false)
const deleteError = ref<string | null>(null)

function openDeleteModal() {
  deleteError.value = null
  isDeleteModalOpen.value = true
}

function closeDeleteModal() {
  if (isDeleting.value) return
  isDeleteModalOpen.value = false
}

async function confirmDelete() {
  if (isDeleting.value) return
  isDeleting.value = true
  deleteError.value = null
  try {
    await useApi()('/api/profile', { method: 'DELETE' })
    authStore.user = null
    await navigateTo('/auth/login')
  } catch {
    isDeleting.value = false
    deleteError.value = 'Не вдалося видалити акаунт. Спробуйте пізніше'
  }
}
</script>

<template>
  <div class="max-w-2xl space-y-8">
    <h1 class="text-2xl font-semibold">Профіль</h1>

    <!-- Section 1: Profile info -->
    <section data-test="profile-info" class="rounded-md border p-4 space-y-3">
      <h2 class="text-lg font-medium">Особисті дані</h2>

      <div>
        <label class="block text-xs font-medium text-gray-600 mb-1">Email</label>
        <input
          :value="authStore.user?.email ?? ''"
          type="email"
          readonly
          data-test="profile-email"
          class="w-full border rounded-md px-3 py-2 bg-gray-50 text-gray-700"
        />
      </div>

      <div v-if="!isEditingName">
        <label class="block text-xs font-medium text-gray-600 mb-1">Ім'я</label>
        <div class="flex items-center gap-3">
          <span data-test="profile-name" class="flex-1 text-sm">
            {{ authStore.user?.name }}
          </span>
          <button
            type="button"
            data-test="edit-name-button"
            class="px-3 py-1 text-sm border rounded hover:bg-gray-50"
            @click="startNameEdit"
          >
            Редагувати
          </button>
        </div>
        <p v-if="nameStatus === 'saved'" data-test="name-saved" class="text-sm text-green-700 mt-2">
          Ім'я збережено
        </p>
      </div>

      <form v-else class="space-y-2" @submit.prevent="saveName">
        <label for="profile-name-input" class="block text-xs font-medium text-gray-600 mb-1">Ім'я</label>
        <input
          id="profile-name-input"
          v-model="draftName"
          name="name"
          type="text"
          maxlength="100"
          data-test="name-input"
          class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
        <p v-if="nameError" data-test="name-error" class="text-sm text-red-600">{{ nameError }}</p>

        <div class="flex gap-2">
          <button
            type="submit"
            :disabled="nameStatus === 'saving'"
            data-test="save-name-button"
            class="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
          >
            {{ nameStatus === 'saving' ? 'Збереження…' : 'Зберегти' }}
          </button>
          <button
            type="button"
            :disabled="nameStatus === 'saving'"
            class="px-3 py-1 text-sm border rounded hover:bg-gray-50 disabled:opacity-50"
            @click="cancelNameEdit"
          >
            Скасувати
          </button>
        </div>
      </form>
    </section>

    <!-- Section 2: Email verification (pending only) -->
    <section
      v-if="authStore.isPending"
      data-test="resend-section"
      class="rounded-md border border-amber-300 bg-amber-50 p-4 space-y-3"
    >
      <h2 class="text-lg font-medium text-amber-900">Підтвердження email</h2>
      <p class="text-sm text-amber-900">Ваш email не підтверджено</p>

      <p v-if="resendStatus === 'sent'" data-test="resend-sent" class="text-sm text-green-700">
        Якщо акаунт існує, ви отримаєте лист найближчим часом.
      </p>
      <p v-if="resendStatus === 'cooldown'" data-test="resend-cooldown" class="text-sm text-gray-700">
        Почекайте 60 секунд
      </p>
      <p v-if="resendStatus === 'error'" data-test="resend-error" class="text-sm text-red-600">
        Не вдалося надіслати лист. Спробуйте пізніше
      </p>

      <button
        type="button"
        data-test="resend-button"
        :disabled="resendCooldown > 0"
        class="px-3 py-1 text-sm bg-amber-700 text-white rounded hover:bg-amber-800 disabled:opacity-50"
        @click="onResend"
      >
        {{ resendCooldown > 0 ? `Зачекайте ${resendCooldown} с` : 'Надіслати лист повторно' }}
      </button>
    </section>

    <!-- Section 3: Change password -->
    <section data-test="change-password-section" class="rounded-md border p-4 space-y-3">
      <h2 class="text-lg font-medium">Зміна пароля</h2>

      <form
        novalidate
        data-test="change-password-form"
        class="space-y-3"
        @submit.prevent="onChangePassword"
      >
        <div>
          <label for="currentPassword" class="block text-sm font-medium mb-1">Поточний пароль</label>
          <input
            id="currentPassword"
            v-model="currentPassword"
            v-bind="currentPasswordAttrs"
            name="currentPassword"
            type="password"
            autocomplete="current-password"
            class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="changePwdErrors.currentPassword" data-test="current-password-error" class="text-sm text-red-600 mt-1">
            {{ changePwdErrors.currentPassword }}
          </p>
        </div>

        <div>
          <label for="newPassword" class="block text-sm font-medium mb-1">Новий пароль</label>
          <input
            id="newPassword"
            v-model="newPassword"
            v-bind="newPasswordAttrs"
            name="newPassword"
            type="password"
            autocomplete="new-password"
            class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="changePwdErrors.newPassword" data-test="new-password-error" class="text-sm text-red-600 mt-1">
            {{ changePwdErrors.newPassword }}
          </p>
        </div>

        <div>
          <label for="confirmPassword" class="block text-sm font-medium mb-1">Підтвердження нового пароля</label>
          <input
            id="confirmPassword"
            v-model="confirmPassword"
            v-bind="confirmPasswordAttrs"
            name="confirmPassword"
            type="password"
            autocomplete="new-password"
            class="w-full border rounded-md px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          />
          <p v-if="changePwdErrors.confirmPassword" data-test="confirm-password-error" class="text-sm text-red-600 mt-1">
            {{ changePwdErrors.confirmPassword }}
          </p>
        </div>

        <p v-if="changePwdStatus === 'success'" data-test="change-password-success" class="text-sm text-green-700">
          Пароль змінено
        </p>
        <p v-if="changePwdStatus === 'error' && changePwdError" data-test="change-password-error" class="text-sm text-red-600">
          {{ changePwdError }}
        </p>

        <button
          type="submit"
          :disabled="isChangingPwd"
          class="px-3 py-1 text-sm bg-blue-600 text-white rounded hover:bg-blue-700 disabled:opacity-50"
        >
          {{ isChangingPwd ? 'Зачекайте…' : 'Змінити пароль' }}
        </button>
      </form>
    </section>

    <!-- Section 4: Terminate all sessions -->
    <section data-test="sessions-section" class="rounded-md border p-4 space-y-3">
      <h2 class="text-lg font-medium">Сесії</h2>
      <p class="text-sm text-gray-600">
        Завершення всіх сесій вийде з акаунта на цьому та всіх інших пристроях.
      </p>
      <p v-if="terminateError" data-test="terminate-error" class="text-sm text-red-600">
        {{ terminateError }}
      </p>
      <button
        type="button"
        data-test="terminate-all-button"
        :disabled="isTerminating"
        class="px-3 py-1 text-sm border border-red-600 text-red-700 rounded hover:bg-red-50 disabled:opacity-50"
        @click="onTerminateAllSessions"
      >
        {{ isTerminating ? 'Завершення…' : 'Завершити всі сесії' }}
      </button>
    </section>

    <!-- Section 5: Delete account -->
    <section data-test="delete-account-section" class="rounded-md border border-red-200 p-4 space-y-3">
      <h2 class="text-lg font-medium text-red-700">Видалення акаунта</h2>
      <p class="text-sm text-gray-600">
        Дія незворотна. Усі ваші проекти, боти та підписники будуть видалені.
      </p>
      <button
        type="button"
        data-test="delete-account-button"
        class="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700"
        @click="openDeleteModal"
      >
        Видалити акаунт
      </button>
    </section>

    <!-- Delete confirmation modal -->
    <div
      v-if="isDeleteModalOpen"
      data-test="delete-account-modal"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="delete-modal-title"
    >
      <div class="w-full max-w-md rounded-md bg-white p-5 shadow-lg space-y-4">
        <h3 id="delete-modal-title" class="text-lg font-semibold">
          Видалити акаунт?
        </h3>
        <div class="text-sm text-gray-700 space-y-2">
          <p>Це видалить:</p>
          <ul class="list-disc pl-5 space-y-1">
            <li>всі ваші проекти</li>
            <li>ботів</li>
            <li>підписників</li>
          </ul>
          <p>Дію не можна скасувати.</p>
        </div>

        <p v-if="deleteError" data-test="delete-account-error" class="text-sm text-red-600">
          {{ deleteError }}
        </p>

        <div class="flex justify-end gap-2 pt-2">
          <button
            type="button"
            :disabled="isDeleting"
            class="px-3 py-1 text-sm border rounded hover:bg-gray-50 disabled:opacity-50"
            @click="closeDeleteModal"
          >
            Скасувати
          </button>
          <button
            type="button"
            data-test="delete-account-confirm"
            :disabled="isDeleting"
            class="px-3 py-1 text-sm bg-red-600 text-white rounded hover:bg-red-700 disabled:opacity-50"
            @click="confirmDelete"
          >
            {{ isDeleting ? 'Видалення…' : 'Видалити акаунт' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
