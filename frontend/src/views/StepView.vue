<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：横向工序管道。
// 上料 → 贴片 → 回流焊 → AOI → 目检 五个节点由一条管道串起来，
// 管道里有一枚「在制」游标：已完成节点实心点亮、进行中节点呼吸闪烁、待执行是空心，
// 游标停在第一个没完成的节点上。点节点就地开工 / 完工 / 退回，没有任何弹窗。
// ------------------------------------------------------------------

const orders = ref([])
const steps = ref([])
const blockInfo = ref(null)
const selectedId = ref(null)
const error = ref('')
const tip = ref('')
const operator = ref('王强')

const STEPS = ['上料', '贴片', '回流焊', 'AOI', '目检']

const stepMap = computed(() => {
  const m = {}
  for (const s of steps.value) m[s.stepName] = s
  return m
})

// 游标：第一个未完成的工序
const cursor = computed(() => {
  for (const name of STEPS) {
    const s = stepMap.value[name]
    if (!s || s.status !== '已完成') return name
  }
  return null
})

const doneCount = computed(() => STEPS.filter((n) => stepMap.value[n] && stepMap.value[n].status === '已完成').length)

// 返修中工序（游标回炉停线）
const repairingSteps = computed(() =>
  STEPS.filter((n) => stepMap.value[n] && stepMap.value[n].status === '返修中'))

async function load() {
  error.value = ''
  try {
    orders.value = await api.listOrders()
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
    await loadSteps()
  } catch (e) { error.value = e.message }
}

async function loadSteps() {
  if (!selectedId.value) { steps.value = []; blockInfo.value = null; return }
  steps.value = await api.listSteps({ orderId: selectedId.value })
  try {
    blockInfo.value = await api.getLineChangeBlocking(selectedId.value)
  } catch (e) { blockInfo.value = null }
}

async function act(name, kind) {
  const s = stepMap.value[name]
  if (!s) { error.value = `工单还没有「${name}」这道工序，请先投产`; return }
  // 返修中是闭环游标：只能由质检复验推进，节点上直接给出引导，不发任何写请求
  if (s.status === '返修中') {
    error.value = `工序「${name}」返修中：返修工完工后必须由质检员复验通过才能恢复，请到「质检与返修」页处理`
    return
  }
  error.value = ''; tip.value = ''
  try {
    if (kind === 'start') await api.startStep(s.id, operator.value)
    else if (kind === 'finish') await api.finishStep(s.id)
    else await api.resetStep(s.id)
    tip.value = `「${name}」已${kind === 'start' ? '开工' : kind === 'finish' ? '完工' : '退回'}`
    await loadSteps()
  } catch (e) { error.value = e.message }
}

watch(selectedId, () => loadSteps())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">SMT 工序 · 横向管道</span>
        <span class="panel-desc">前一道没完成不能开下一道；点节点就地开工 / 完工 / 退回</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div v-if="blockInfo && blockInfo.blocked && blockInfo.batch" class="lc-banner">
        换线批次 V{{ blockInfo.batch.versionNo }}（{{ blockInfo.batch.status }}）未放行，贴片工序已暂停 ——
        {{ blockInfo.nextAction }}；处理人：{{ blockInfo.handler }}。
        到「换线首件」页完成首件确认后才能恢复。
      </div>

      <div v-if="repairingSteps.length" class="rp-banner">
        工序返修中：{{ repairingSteps.join('、') }} —— 返修工完工后须经质检员复验通过才能恢复，
        返修闭环未完成前该工序不能开工、完工或跳过。请到「质检与返修」页处理。
      </div>

      <div class="stat-row">
        <div class="stat"><div class="k">已完成工序</div><div class="v">{{ doneCount }} / {{ STEPS.length }}</div></div>
        <div class="stat"><div class="k">游标位置</div><div class="v">{{ cursor || '全部完成' }}</div></div>
      </div>

      <div class="pick-row">
        <span class="pick-label">工单</span>
        <select v-model="selectedId" class="fld">
          <option v-for="o in orders" :key="o.id" :value="o.id">{{ o.orderNo }} · {{ o.productModel }}</option>
        </select>
        <span class="pick-label">操作员</span>
        <input v-model="operator" class="fld" style="width:110px" />
      </div>

      <div class="pipe">
        <div class="pipe-line"></div>
        <div class="pipe-flow" :style="{ width: (doneCount / (STEPS.length - 1)) * 100 + '%' }"></div>

        <div v-for="(name, i) in STEPS" :key="name" class="pipe-node">
          <div
            class="node"
            :class="[
              stepMap[name] ? 'st-' + stepMap[name].status : 'st-none',
              { cursor: cursor === name, repairing: stepMap[name] && stepMap[name].status === '返修中' }
            ]"
            @click="act(name, stepMap[name] && stepMap[name].status === '已完成' ? 'reset' : (stepMap[name] && stepMap[name].status === '进行中' ? 'finish' : 'start'))"
          >
            <span class="node-i">{{ i + 1 }}</span>
          </div>
          <div class="node-name">{{ name }}</div>
          <div class="node-state">{{ stepMap[name] ? stepMap[name].status : '未建立' }}</div>
          <div class="node-op">{{ stepMap[name] && stepMap[name].operator ? stepMap[name].operator : '—' }}</div>
        </div>
      </div>

      <div class="note">灰色空心 = 待执行／未建立；蓝闪 = 进行中；橙色呼吸 = 返修中（仅质检复验可解除）；墨蓝实心 = 已完成。</div>
    </div>
  </div>
</template>

<style scoped>
.pick-row { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; flex-wrap: wrap; }
.pick-label { font-size: 13px; color: var(--muted); }

.pipe { position: relative; display: grid; grid-template-columns: repeat(5, 1fr); padding: 34px 0 8px; }
.pipe-line {
  position: absolute; left: 10%; right: 10%; top: 46px; height: 6px;
  background: #e4e6f3; border-radius: 3px;
}
.pipe-flow {
  position: absolute; left: 10%; top: 46px; height: 6px;
  background: linear-gradient(90deg, var(--primary), var(--primary-soft));
  border-radius: 3px; transition: width 0.25s;
}

.pipe-node { display: flex; flex-direction: column; align-items: center; gap: 3px; z-index: 1; }

.node {
  width: 44px; height: 44px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  border: 3px solid #cfd3ea; background: #fff; cursor: pointer;
  font-weight: 800; color: var(--muted);
  transition: transform 0.14s, box-shadow 0.14s;
}
.node:hover { transform: translateY(-2px); }
.node-i { font-size: 15px; }

.node.st-待执行 { border-color: #cfd3ea; color: var(--muted); }
.node.st-进行中 {
  border-color: var(--primary-soft); color: var(--primary-deep);
  animation: pulse 1.4s infinite;
}
.node.st-返修中 {
  border-color: #ef6c00; background: #ef6c00; color: #fff;
  animation: repairPulse 1.4s infinite;
}
.node.st-已完成 {
  border-color: var(--primary); background: var(--primary); color: #fff;
}
.node.st-none { border-style: dashed; opacity: 0.5; }

.node.cursor { box-shadow: 0 0 0 4px var(--primary-wash); }
.node.repairing { box-shadow: 0 0 0 4px #ffe0b2; }

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(63, 81, 181, 0.4); }
  50% { box-shadow: 0 0 0 8px rgba(63, 81, 181, 0); }
}

@keyframes repairPulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(239, 108, 0, 0.45); }
  50% { box-shadow: 0 0 0 9px rgba(239, 108, 0, 0); }
}

.node-name { font-size: 13px; font-weight: 800; color: var(--primary-deep); margin-top: 4px; }
.node-state { font-size: 11px; color: var(--muted); }
.node-op { font-size: 10px; color: var(--muted); }

.note { font-size: 12px; color: var(--muted); margin-top: 10px; }

.lc-banner {
  border: 1px solid #f5c6c2; background: #fdecea; color: #a32b20;
  padding: 8px 12px; border-radius: 8px; margin-bottom: 10px; font-size: 13px; font-weight: 700;
}

.rp-banner {
  border: 1px solid #ffcc80; background: #fff3e0; color: #e65100;
  padding: 8px 12px; border-radius: 8px; margin-bottom: 10px; font-size: 13px; font-weight: 700;
}
</style>
