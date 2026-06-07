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

// Add dialog: picks a StepType + fills the per-type form (shared FunnelStepForm), then emits the new
// step. The parent appends it to the END of the steps array (position = order). No initial → SEND_MESSAGE.
// siblingSteps = the funnel's other steps, threaded into the form so a MENU callback button can target one.
const props = defineProps<{ open: boolean; siblingSteps?: FunnelStep[] }>()
const emit = defineEmits<{ 'update:open': [value: boolean]; add: [step: FunnelStep] }>()

const { t } = useI18n()
// Remount the form per open so a re-opened dialog starts clean (key bumped on each open).
const formKey = ref(0)
watch(
  () => props.open,
  (open) => {
    if (open) formKey.value++
  },
)

function onSubmit(step: FunnelStep) {
  emit('add', step)
  emit('update:open', false)
}
</script>

<template>
  <Dialog :open="props.open" @update:open="emit('update:open', $event)">
    <DialogScrollContent>
      <DialogHeader>
        <DialogTitle>{{ t('funnels.steps.dialog.addTitle') }}</DialogTitle>
        <DialogDescription class="sr-only">{{ t('funnels.steps.dialog.addTitle') }}</DialogDescription>
      </DialogHeader>
      <FunnelStepForm
        :key="formKey"
        :sibling-steps="props.siblingSteps"
        :submit-label="t('funnels.steps.form.submitAdd')"
        @submit="onSubmit"
        @cancel="emit('update:open', false)"
      />
    </DialogScrollContent>
  </Dialog>
</template>
