<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：BOM 阶梯条。
// 选中工单后，BOM 每一行画成一组上下两条：上面是「本单需求」的实心条，
// 下面叠一条「现有库存」的半透明条；库存条短于需求条的部分就是缺口，
// 缺口直接标红并给出还差多少颗。右边是物料台账，可就地入库 / 领料。
// 顶部一枚齐套率环，齐套不通过时「投产」不可点。
// ------------------------------------------------------------------

const orders = ref([])
const components = ref([])
const shortages = ref([])
const kitInfo = ref(null)
const selectedId = ref(null)
const error = ref('')
const tip = ref('')
const restockQty = ref(100)

const selected = computed(() => orders.value.find((o) => o.id === selectedId.value) || null)

// BOM 阶梯条：需求 = 单板用量 × 计划板数
const kitRows = computed(() => {
  if (!selected.value) return []
  return components.value.map((c) => {
    const need = (c.unitQty || 0) * selected.value.planQty
    const stock = c.stockQty || 0
    return {
      partNo: c.partNo,
      partName: c.partName,
      unitQty: c.unitQty || 0,
      need,
      stock,
      gap: Math.max(0, need - stock),
      status: c.status,
      usable: c.status === '正常' && stock > 0
    }
  })
})

const maxNeed = computed(() => {
  let m = 1
  for (const r of kitRows.value) m = Math.max(m, r.need)
  return m
})

async function load() {
  error.value = ''
  try {
    orders.value = await api.listOrders()
    components.value = await api.listComponents()
    shortages.value = await api.shortageOrders()
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
    await check()
  } catch (e) { error.value = e.message }
}

async function check() {
  if (!selectedId.value) return
  try {
    kitInfo.value = await api.kitCheck(selectedId.value)
  } catch (e) { error.value = e.message }
}

async function restock(c) {
  error.value = ''; tip.value = ''
  try {
    await api.restockComponent(c.id, restockQty.value)
    tip.value = `${c.partNo} 已入库 ${restockQty.value} 颗`
    await load()
  } catch (e) { error.value = e.message }
}

async function issue(c) {
  error.value = ''; tip.value = ''
  try {
    await api.issueComponent(c.id, restockQty.value)
    tip.value = `${c.partNo} 已领料 ${restockQty.value} 颗`
    await load()
  } catch (e) { error.value = e.message }
}

function ringStyle(rate) {
  const deg = Math.min(360, (Number(rate) / 100) * 360)
  return { background: `conic-gradient(var(--primary) ${deg}deg, #e6e8f5 ${deg}deg)` }
}

function w(v) {
  return Math.min(100, (Number(v) / maxNeed.value) * 100) + '%'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">齐套备料 · BOM 阶梯条</span>
        <span class="panel-desc">上条是需求、下条是库存，短出来的那段就是缺口</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">物料品种</div><div class="v">{{ components.length }}</div></div>
        <div class="stat"><div class="k">缺料工单</div><div class="v">{{ shortages.length }}</div></div>
        <div class="stat"><div class="k">齐套率</div>
          <div class="v">{{ kitInfo ? kitInfo.kitRate : 0 }}%</div>
        </div>
        <div class="stat"><div class="k">BOM 行</div><div class="v">{{ kitInfo ? kitInfo.bomLines : 0 }}</div></div>
      </div>

      <div class="pick-row">
        <span class="pick-label">工单</span>
        <select v-model="selectedId" class="fld" @change="check">
          <option v-for="o in orders" :key="o.id" :value="o.id">
            {{ o.orderNo }} · {{ o.productModel }} · {{ o.planQty }} 板
          </option>
        </select>
        <div v-if="kitInfo" class="ring" :style="ringStyle(kitInfo.kitRate)">
          <div class="ring-in">
            <div class="ring-v">{{ kitInfo.kitRate }}%</div>
            <div class="ring-k">齐套</div>
          </div>
        </div>
        <span v-if="kitInfo" class="verdict" :class="kitInfo.ok ? 'good' : 'bad'">
          {{ kitInfo.ok ? '齐套通过，可投产' : `缺 ${kitInfo.shortageLines} 项，齐套不足` }}
        </span>
      </div>

      <div class="kit-list">
        <div v-for="r in kitRows" :key="r.partNo" class="kit-row" :class="{ short: r.gap > 0 || !r.usable }">
          <span class="kp">{{ r.partNo }}</span>
          <span class="kn">{{ r.partName }}</span>
          <div class="bars">
            <div class="bar need" :style="{ width: w(r.need) }"><span>需求 {{ r.need }}</span></div>
            <div class="bar stock" :style="{ width: w(r.stock) }"><span>库存 {{ r.stock }}</span></div>
          </div>
          <span class="kgap">{{ r.gap > 0 ? '缺 ' + r.gap + ' 颗' : (r.usable ? '齐备' : '不可用') }}</span>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">物料台账</span>
        <span class="panel-desc">就地入库 / 领料，改完库存立刻反映到上面的阶梯条</span>
      </div>
      <div class="qty-row">
        <span class="pick-label">数量</span>
        <input type="number" min="1" v-model.number="restockQty" class="fld n" />
      </div>
      <div class="comp-list">
        <div v-for="c in components" :key="c.id" class="comp-row">
          <span class="cp">{{ c.partNo }}</span>
          <span class="cn">{{ c.partName }}</span>
          <span class="cpk">{{ c.packageType }}</span>
          <span class="cst">{{ c.status }}</span>
          <span class="cs">{{ c.stockQty }} 颗</span>
          <button class="act sm" @click="restock(c)">入库</button>
          <button class="act sm" :disabled="c.status !== '正常'" @click="issue(c)">领料</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pick-row, .qty-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.pick-label { font-size: 13px; color: var(--muted); }

.ring { width: 54px; height: 54px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; }
.ring-in { width: 40px; height: 40px; border-radius: 50%; background: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.ring-v { font-size: 12px; font-weight: 800; color: var(--primary-deep); }
.ring-k { font-size: 9px; color: var(--muted); }

.verdict { font-size: 13px; font-weight: 700; }
.verdict.good { color: #2e7d32; }
.verdict.bad { color: #c62828; }

.kit-list { display: flex; flex-direction: column; gap: 6px; }
.kit-row {
  display: grid; grid-template-columns: 120px 1fr 300px 96px;
  align-items: center; gap: 10px;
  padding: 7px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px;
}
.kit-row.short { border-color: #ef9a9a; background: #fff6f6; }
.kp { font-weight: 800; color: var(--primary-deep); }
.kn, .cn { color: var(--ink); }

.bars { display: flex; flex-direction: column; gap: 3px; }
.bar { height: 15px; border-radius: 3px; display: flex; align-items: center; padding-left: 6px; }
.bar.need { background: linear-gradient(90deg, var(--primary), var(--primary-soft)); color: #fff; font-size: 10px; }
.bar.stock { background: #ffcc80; color: #5d4037; font-size: 10px; }
.kgap { text-align: right; font-weight: 700; font-size: 12px; }
.kit-row.short .kgap { color: #c62828; }

.comp-list { display: flex; flex-direction: column; gap: 5px; }
.comp-row { display: grid; grid-template-columns: 120px 1fr 70px 70px 84px 62px 62px; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; }
.cpk, .cst, .cs { color: var(--muted); }
</style>
