<template>
  <div class="ac" ref="containerRef">
    <!-- Character 1: Amber tall rectangle (back layer) -->
    <div
      ref="amberRef"
      class="ac-char ac-amber"
      :class="{ 'is-tall': isTyping || isHidingPassword }"
      :style="{ transform: amberTransform }"
    >
      <div
        class="ac-eyes"
        :style="{
          left: `${45 + s.amber.faceX}px`,
          top: `${40 + s.amber.faceY}px`,
        }"
      >
        <div class="ac-eyeball" :class="{ 'is-blink': amberBlink, 'is-squeeze': isHidingPassword }" v-for="i in 2" :key="'ae'+i">
          <div v-if="!amberBlink" class="ac-pupil" :style="{ transform: `translate(${s.amber.pupilX}px, ${s.amber.pupilY}px)` }"></div>
        </div>
      </div>
      <!-- hands covering eyes when hiding password -->
      <transition name="ac-hands">
        <div v-if="isHidingPassword" class="ac-hands ac-hands--amber">
          <div class="ac-hand ac-hand--l"></div>
          <div class="ac-hand ac-hand--r"></div>
        </div>
      </transition>
    </div>

    <!-- Character 2: Dark brown tall rectangle (middle layer) -->
    <div
      ref="darkRef"
      class="ac-char ac-dark"
      :style="{ transform: darkTransform }"
    >
      <div
        class="ac-eyes"
        :style="{
          left: `${26 + s.dark.faceX}px`,
          top: `${32 + s.dark.faceY}px`,
        }"
      >
        <div class="ac-eyeball ac-eyeball-sm" :class="{ 'is-blink': darkBlink, 'is-squeeze': isHidingPassword }" v-for="i in 2" :key="'de'+i">
          <div v-if="!darkBlink" class="ac-pupil ac-pupil-sm" :style="{ transform: `translate(${s.dark.pupilX}px, ${s.dark.pupilY}px)` }"></div>
        </div>
      </div>
      <transition name="ac-hands">
        <div v-if="isHidingPassword" class="ac-hands ac-hands--dark">
          <div class="ac-hand ac-hand--sm ac-hand--l"></div>
          <div class="ac-hand ac-hand--sm ac-hand--r"></div>
        </div>
      </transition>
    </div>

    <!-- Character 3: Peach semi-circle (front left) -->
    <div
      ref="peachRef"
      class="ac-char ac-peach"
      :style="{ transform: peachTransform }"
    >
      <div
        class="ac-dots"
        :style="{
          left: `${82 + s.peach.faceX}px`,
          top: `${90 + s.peach.faceY}px`,
        }"
      >
        <div class="ac-dot" :style="{ transform: `translate(${s.peach.pupilX}px, ${s.peach.pupilY}px)` }"></div>
        <div class="ac-dot" :style="{ transform: `translate(${s.peach.pupilX}px, ${s.peach.pupilY}px)` }"></div>
      </div>
    </div>

    <!-- Character 4: Gold rounded rectangle (front right) -->
    <div
      ref="goldRef"
      class="ac-char ac-gold"
      :style="{ transform: goldTransform }"
    >
      <div
        class="ac-dots"
        :style="{
          left: `${52 + s.gold.faceX}px`,
          top: `${40 + s.gold.faceY}px`,
        }"
      >
        <div class="ac-dot" :style="{ transform: `translate(${s.gold.pupilX}px, ${s.gold.pupilY}px)` }"></div>
        <div class="ac-dot" :style="{ transform: `translate(${s.gold.pupilX}px, ${s.gold.pupilY}px)` }"></div>
      </div>
      <!-- mouth with expressions -->
      <div
        class="ac-mouth"
        :class="{
          'is-worry': isHidingPassword,
        }"
        :style="{
          left: `${40 + s.gold.faceX}px`,
          top: `${88 + s.gold.faceY}px`,
        }"
      ></div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'

const props = defineProps({
  isTyping: { type: Boolean, default: false },
  showPassword: { type: Boolean, default: false },
  passwordLength: { type: Number, default: 0 }
})

const containerRef = ref(null)
const amberRef = ref(null)
const darkRef = ref(null)
const peachRef = ref(null)
const goldRef = ref(null)

const mouse = reactive({ x: 0, y: 0 })
const amberBlink = ref(false)
const darkBlink = ref(false)
const isLookingAtEachOther = ref(false)

const isHidingPassword = computed(() => props.passwordLength > 0 && !props.showPassword)


// --- per-character smooth state (all driven by rAF) ---
const s = reactive({
  amber: { skew: 0, tx: 0, idleY: 0, faceX: 0, faceY: 0, pupilX: 0, pupilY: 0 },
  dark:  { skew: 0, tx: 0, idleY: 0, faceX: 0, faceY: 0, pupilX: 0, pupilY: 0 },
  peach: { skew: 0, tx: 0, idleY: 0, faceX: 0, faceY: 0, pupilX: 0, pupilY: 0 },
  gold:  { skew: 0, tx: 0, idleY: 0, faceX: 0, faceY: 0, pupilX: 0, pupilY: 0 },
})

// --- composed transform strings (reactive, update every frame) ---
const amberTransform = computed(() =>
  `skewX(${s.amber.skew}deg) translateY(${s.amber.idleY}px) translateX(${s.amber.tx}px)`
)
const darkTransform = computed(() =>
  `skewX(${s.dark.skew}deg) translateY(${s.dark.idleY}px) translateX(${s.dark.tx}px)`
)
const peachTransform = computed(() =>
  `skewX(${s.peach.skew}deg) translateY(${s.peach.idleY}px) translateX(${s.peach.tx}px)`
)
const goldTransform = computed(() =>
  `skewX(${s.gold.skew}deg) translateY(${s.gold.idleY}px) translateX(${s.gold.tx}px)`
)

function onMouseMove(e) {
  mouse.x = e.clientX
  mouse.y = e.clientY
}

// --- lerp ---
const LERP_BODY = 0.07
const LERP_FACE = 0.09
const LERP_PUPIL = 0.16

function lerp(cur, tgt, f) {
  return cur + (tgt - cur) * f
}

// --- rAF loop: compute ALL motion here ---
let rafId = null

function animationLoop(timestamp) {
  const now = timestamp / 1000
  const hiding = isHidingPassword.value
  const typing = props.isTyping
  const looking = isLookingAtEachOther.value

  // ─── AMBER ───
  const rawAmber = calcPosition(amberRef)
  let amberSkewT = rawAmber.bodySkew
  let amberTxT = 0
  if (hiding) { amberSkewT -= 10; amberTxT = 25 }
  else if (typing) { amberSkewT -= 4; amberTxT = 6 }

  s.amber.skew = lerp(s.amber.skew, amberSkewT, LERP_BODY)
  s.amber.tx = lerp(s.amber.tx, amberTxT, LERP_BODY)
  s.amber.idleY = Math.sin(now * 0.8) * 4

  let amberFxT = hiding ? 0 : (looking ? 15 : rawAmber.faceX)
  let amberFyT = hiding ? 5 : (looking ? 10 : rawAmber.faceY)
  s.amber.faceX = lerp(s.amber.faceX, amberFxT, LERP_FACE)
  s.amber.faceY = lerp(s.amber.faceY, amberFyT, LERP_FACE)

  const rawAP = calcPupil(amberRef, 5)
  s.amber.pupilX = lerp(s.amber.pupilX, hiding ? 0 : (looking ? 3 : rawAP.x), LERP_PUPIL)
  s.amber.pupilY = lerp(s.amber.pupilY, hiding ? 0 : (looking ? 4 : rawAP.y), LERP_PUPIL)

  // ─── DARK ───
  const rawDark = calcPosition(darkRef)
  let darkSkewT = rawDark.bodySkew
  let darkTxT = 0
  if (hiding) { darkSkewT += 8; darkTxT = -15 }
  else if (looking) { darkSkewT += 6; darkTxT = 12 }

  s.dark.skew = lerp(s.dark.skew, darkSkewT, LERP_BODY)
  s.dark.tx = lerp(s.dark.tx, darkTxT, LERP_BODY)
  s.dark.idleY = Math.sin(now * 0.7 + 1.2) * 3

  let darkFxT = hiding ? -5 : (looking ? -8 : rawDark.faceX)
  let darkFyT = hiding ? 3 : (looking ? -5 : rawDark.faceY)
  s.dark.faceX = lerp(s.dark.faceX, darkFxT, LERP_FACE)
  s.dark.faceY = lerp(s.dark.faceY, darkFyT, LERP_FACE)

  const rawDP = calcPupil(darkRef, 4)
  s.dark.pupilX = lerp(s.dark.pupilX, hiding ? 0 : (looking ? -3 : rawDP.x), LERP_PUPIL)
  s.dark.pupilY = lerp(s.dark.pupilY, hiding ? 0 : (looking ? 4 : rawDP.y), LERP_PUPIL)

  // ─── PEACH ───
  const rawPeach = calcPosition(peachRef)
  let peachSkewT = rawPeach.bodySkew
  let peachTxT = 0
  if (hiding) { peachSkewT -= 5; peachTxT = -10 }

  s.peach.skew = lerp(s.peach.skew, peachSkewT, LERP_BODY)
  s.peach.tx = lerp(s.peach.tx, peachTxT, LERP_BODY)
  s.peach.idleY = Math.sin(now * 0.9 + 0.5) * 5

  let peachFxT = hiding ? -8 : rawPeach.faceX
  let peachFyT = hiding ? 5 : rawPeach.faceY
  s.peach.faceX = lerp(s.peach.faceX, peachFxT, LERP_FACE)
  s.peach.faceY = lerp(s.peach.faceY, peachFyT, LERP_FACE)

  const rawPP = calcPupil(peachRef, 5)
  s.peach.pupilX = lerp(s.peach.pupilX, hiding ? 0 : rawPP.x, LERP_PUPIL)
  s.peach.pupilY = lerp(s.peach.pupilY, hiding ? 0 : rawPP.y, LERP_PUPIL)

  // ─── GOLD ───
  const rawGold = calcPosition(goldRef)
  let goldSkewT = rawGold.bodySkew
  let goldTxT = 0
  if (hiding) { goldSkewT += 6; goldTxT = 10 }

  s.gold.skew = lerp(s.gold.skew, goldSkewT, LERP_BODY)
  s.gold.tx = lerp(s.gold.tx, goldTxT, LERP_BODY)
  s.gold.idleY = Math.sin(now * 0.6 + 2.0) * 3.5

  let goldFxT = hiding ? 6 : rawGold.faceX
  let goldFyT = hiding ? 4 : rawGold.faceY
  s.gold.faceX = lerp(s.gold.faceX, goldFxT, LERP_FACE)
  s.gold.faceY = lerp(s.gold.faceY, goldFyT, LERP_FACE)

  const rawGP = calcPupil(goldRef, 5)
  s.gold.pupilX = lerp(s.gold.pupilX, hiding ? 0 : rawGP.x, LERP_PUPIL)
  s.gold.pupilY = lerp(s.gold.pupilY, hiding ? 0 : rawGP.y, LERP_PUPIL)

  rafId = requestAnimationFrame(animationLoop)
}

// --- lifecycle ---
onMounted(() => {
  window.addEventListener('mousemove', onMouseMove)
  scheduleAmberBlink()
  scheduleDarkBlink()
  rafId = requestAnimationFrame(animationLoop)
})

onUnmounted(() => {
  window.removeEventListener('mousemove', onMouseMove)
  cancelAnimationFrame(rafId)
  clearTimeout(amberBlinkTimer)
  clearTimeout(darkBlinkTimer)
})

// --- blink scheduling ---
let amberBlinkTimer = null
let darkBlinkTimer = null

function scheduleAmberBlink() {
  amberBlinkTimer = setTimeout(() => {
    amberBlink.value = true
    setTimeout(() => { amberBlink.value = false; scheduleAmberBlink() }, 120)
  }, Math.random() * 3500 + 2500)
}

function scheduleDarkBlink() {
  darkBlinkTimer = setTimeout(() => {
    darkBlink.value = true
    setTimeout(() => { darkBlink.value = false; scheduleDarkBlink() }, 120)
  }, Math.random() * 3500 + 2500)
}

// --- look at each other on typing focus ---
watch(() => props.isTyping, (val) => {
  if (val) {
    isLookingAtEachOther.value = true
    setTimeout(() => { isLookingAtEachOther.value = false }, 800)
  }
})

// --- position calculators ---
function calcPosition(elRef) {
  const el = elRef.value
  if (!el) return { faceX: 0, faceY: 0, bodySkew: 0 }

  const rect = el.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 3
  const dx = mouse.x - cx
  const dy = mouse.y - cy

  return {
    faceX: Math.max(-15, Math.min(15, dx / 20)),
    faceY: Math.max(-10, Math.min(10, dy / 30)),
    bodySkew: Math.max(-6, Math.min(6, -dx / 120))
  }
}

function calcPupil(elRef, maxDist = 5) {
  const el = elRef.value
  if (!el) return { x: 0, y: 0 }

  const rect = el.getBoundingClientRect()
  const cx = rect.left + rect.width / 2
  const cy = rect.top + rect.height / 2
  const dx = mouse.x - cx
  const dy = mouse.y - cy
  const dist = Math.min(Math.sqrt(dx * dx + dy * dy), maxDist)
  const angle = Math.atan2(dy, dx)
  return { x: Math.cos(angle) * dist, y: Math.sin(angle) * dist }
}
</script>

<style scoped>
.ac {
  position: relative;
  width: 550px;
  height: 400px;
}

/* ── shared character ── */
.ac-char {
  position: absolute;
  bottom: 0;
  transform-origin: bottom center;
  /* NO css animation on transform — all driven by rAF inline style */
}

/* ── amber (back) ── */
.ac-amber {
  left: 70px;
  width: 180px;
  height: 400px;
  background: #B67639;
  border-radius: 10px 10px 0 0;
  z-index: 1;
  transition: height 0.6s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.ac-amber.is-tall {
  height: 435px;
}

/* ── dark brown (middle) ── */
.ac-dark {
  left: 240px;
  width: 120px;
  height: 310px;
  background: #3A2C21;
  border-radius: 8px 8px 0 0;
  z-index: 2;
}

/* ── peach (front left) ── */
.ac-peach {
  left: 0;
  width: 240px;
  height: 200px;
  background: #E8B896;
  border-radius: 120px 120px 0 0;
  z-index: 3;
}

/* ── gold (front right) ── */
.ac-gold {
  left: 310px;
  width: 140px;
  height: 230px;
  background: #D4A84C;
  border-radius: 70px 70px 0 0;
  z-index: 4;
}

/* ── eyes (white eyeballs) ── */
.ac-eyes {
  position: absolute;
  display: flex;
  gap: 32px;
}

.ac-eyeball {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  transition: height 0.15s ease, width 0.15s ease;
}

.ac-eyeball.is-blink {
  height: 2px;
}

.ac-eyeball.is-squeeze {
  height: 4px;
  border-radius: 4px;
}

.ac-eyeball-sm {
  width: 16px;
  height: 16px;
}

.ac-eyeball-sm.is-blink {
  height: 2px;
}

.ac-eyeball-sm.is-squeeze {
  height: 3px;
  border-radius: 3px;
}

.ac-pupil {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #2D2D2D;
}

.ac-pupil-sm {
  width: 6px;
  height: 6px;
}

/* ── dots (no white sclera, just dark pupils) ── */
.ac-dots {
  position: absolute;
  display: flex;
  gap: 32px;
}

.ac-dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #2D2D2D;
}

/* ── mouth ── */
.ac-mouth {
  position: absolute;
  width: 80px;
  height: 4px;
  background: #2D2D2D;
  border-radius: 999px;
  transition:
    width 0.4s cubic-bezier(0.34, 1.56, 0.64, 1),
    height 0.4s cubic-bezier(0.34, 1.56, 0.64, 1),
    border-radius 0.35s ease;
}

.ac-mouth.is-worry {
  width: 28px;
  height: 6px;
  border-radius: 50%;
}


/* ── cover hands (when hiding password) ── */
.ac-hands {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 4px;
  z-index: 5;
}

.ac-hands--amber {
  top: 30px;
}

.ac-hands--dark {
  top: 24px;
}

.ac-hand {
  width: 50px;
  height: 34px;
  border-radius: 16px 16px 12px 12px;
}

.ac-hand--sm {
  width: 42px;
  height: 30px;
  border-radius: 13px 13px 9px 9px;
}

.ac-hands--amber .ac-hand {
  background: linear-gradient(180deg, #DEAD72, #C8923E);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,0.2),
    0 2px 6px rgba(130,80,30,0.25);
}

.ac-hands--dark .ac-hand {
  background: linear-gradient(180deg, #6B5546, #4A3828);
  box-shadow:
    inset 0 1px 0 rgba(255,255,255,0.08),
    0 2px 6px rgba(30,20,10,0.3);
}

.ac-hand--l {
  transform: rotate(-6deg);
}

.ac-hand--r {
  transform: rotate(6deg);
}

/* hand enter/leave transitions */
.ac-hands-enter-active {
  transition: opacity 0.3s ease, transform 0.45s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.ac-hands-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.ac-hands-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(24px);
}

.ac-hands-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(12px);
}

/* ── responsive ── */
@media (max-height: 700px) {
  .ac {
    width: 440px;
    height: 320px;
    transform: scale(0.8);
    transform-origin: bottom center;
  }
}
</style>
