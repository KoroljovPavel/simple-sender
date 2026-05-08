<template>
  <div class="relative min-h-screen flex items-center justify-center bg-gray-50 px-4">
    <LangSwitcher class="absolute top-4 right-4 z-10" />
    <div class="w-full max-w-md bg-white border rounded-lg shadow-sm p-6">
      <slot />
    </div>
  </div>
</template>
