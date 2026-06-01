<script setup lang="ts">
import {
  Dialog,
  DialogScrollContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '~/components/ui/dialog'
import FunnelStepForm from '~/components/funnels/FunnelStepForm.vue'
import type { FunnelStep } from '~/types/funnel'

// Edit dialog: same per-type form (shared FunnelStepForm), PRE-FILLED from the step being edited; emits
// the updated step back to the parent which replaces it in place (order unchanged).
const props = defineProps<{ open: boolean; step: FunnelStep | null; index: number }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; save: [index: number, step: FunnelStep] }>()

const { t } = useI18n()
// Bump the form key whenever a different step opens so initialValues are re-seeded from the new step.
const formKey = ref(0)
watch(
  () => [props.open, props.index] as const,
  ([open]) => {
    if (open) formKey.value++
  },
)

function onSubmit(step: FunnelStep) {
  emit('save', props.index, step)
  emit('update:open', false)
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent>
      <DialogHeader>
        <DialogTitle>{{ t('funnels.steps.dialog.editTitle') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('funnels.steps.dialog.editTitle') }}</DialogDescription>
      </DialogHeader>
      <FunnelStepForm
        v-if="props.step"
        :key="formKey"
        :initial="props.step"
        :submit-label="t('funnels.steps.form.submitSave')"
        @submit="onSubmit"
        @cancel="emit('update:open', false)"
      />
    </DialogScrollContent>
  </Dialog>
</template>
