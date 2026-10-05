<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：优先级泳道。
// 高 / 中 / 低 三条横向泳道各占一行，工单是一张张卡片落在对应优先级的泳道里，
// 逾期卡片整张描红；点卡片就地展开（不是弹窗），展开区里做报工 / 投产 / 推进。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const orders = ref([])
const lcMap = ref({})
const error = ref('')
const tip = ref('')
const openId = ref(null)
const reportQty = ref(50)

const lanes = ['高', '中', '低']
const byLane = computed(() => {
  const m = {}
  for (const p of lanes) m[p] = orders.value.filter((o) => o.priority === p)
  return m
})

const overdue = computed(() =>
  orders.value.filter((o) => o.daysToDue !== null && o.daysToDue < 0).length
)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    const [list, summary] = await Promise.all([
      api.listOrders(q ? { status: q } : {}),
      api.lineChangeSummary()
    ])
    orders.value = list
    if (!orders.value.length && q) orders.value = await api.listOrders()
    const m = {}
    for (const s of summary) m[s.orderId] = s
    lcMap.value = m
  } catch (e) { error.value = e.message }
}

function goLineChange(id) {
  router.push('/line-change?order=' + id)
}

async function release(id) {
  error.value = ''; tip.value = ''
  try {
    await api.releaseOrder(id)
    tip.value = '已投产，SMT 五道工序已铺开'
    await load()
  } catch (e) { error.value = e.message }
}

async function report(id) {
  error.value = ''; tip.value = ''
  try {
    await api.reportOrder(id, reportQty.value)
    tip.value = `已报工 ${reportQty.value} 板`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(id, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferOrder(id, target)
    tip.value = `已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">生产工单 · 优先级泳道</span>
        <span class="panel-desc">卡片按优先级落泳道，点卡片就地展开操作，逾期整张描红</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">工单</div><div class="v">{{ orders.length }}</div></div>
        <div class="stat"><div class="k">待投产</div><div class="v">{{ orders.filter(o => o.status === '待投产').length }}</div></div>
        <div class="stat"><div class="k">贴片中</div><div class="v">{{ orders.filter(o => o.status === '贴片中').length }}</div></div>
        <div class="stat"><div class="k">已逾期</div><div class="v">{{ overdue }}</div></div>
      </div>

      <div v-for="p in lanes" :key="p" class="lane">
        <div class="lane-head">
          <span class="lane-name" :class="'p-' + p">{{ p }}优先级</span>
          <span class="lane-count">{{ (byLane[p] || []).length }} 单</span>
        </div>
        <div class="lane-body">
          <div
            v-for="o in (byLane[p] || [])"
            :key="o.id"
            class="wo-card"
            :class="{ open: openId === o.id, late: o.daysToDue !== null && o.daysToDue < 0 }"
            @click="openId = openId === o.id ? null : o.id"
          >
            <div class="wo-top">
              <span class="wo-no">{{ o.orderNo }}</span>
              <span class="wo-model">{{ o.productModel }}</span>
              <span v-if="lcMap[o.id]" class="wo-lc" :class="{ blocked: lcMap[o.id].blocked }">
                换线V{{ lcMap[o.id].versionNo }}·{{ lcMap[o.id].status }}
              </span>
              <span class="wo-tag">{{ o.status }}</span>
            </div>
            <div class="wo-bar">
              <div class="wo-bar-fill" :style="{ width: Math.min(100, o.progressRate) + '%' }"></div>
            </div>
            <div class="wo-meta">
              {{ o.doneQty }} / {{ o.planQty }} 板 · {{ o.progressRate }}%
              <span v-if="o.daysToDue !== null" :class="{ late: o.daysToDue < 0 }">
                · 距交期 {{ o.daysToDue }} 天
              </span>
            </div>

            <div v-if="openId === o.id" class="wo-detail" @click.stop>
              <div v-if="lcMap[o.id]" class="lc-line" :class="{ blocked: lcMap[o.id].blocked }">
                <template v-if="lcMap[o.id].blocked">
                  换线批次 V{{ lcMap[o.id].versionNo }}（{{ lcMap[o.id].status }}）未放行，贴片暂停 ——
                  {{ lcMap[o.id].nextAction }}；处理人：{{ lcMap[o.id].handler }}
                </template>
                <template v-else>
                  换线批次 V{{ lcMap[o.id].versionNo }}（{{ lcMap[o.id].status }}），贴片不受换线约束
                </template>
                <button class="act sm" @click="goLineChange(o.id)">换线详情</button>
              </div>
              <div class="op-row">
                <button class="act primary" :disabled="o.status !== '待投产'" @click="release(o.id)">投产</button>
                <span class="op-label">报工</span>
                <input type="number" min="1" v-model.number="reportQty" class="fld n" />
                <button class="act" :disabled="o.status !== '贴片中'" @click="report(o.id)">登记</button>
                <button class="act" :disabled="o.status !== '贴片中'" @click="transfer(o.id, '待检验')">转待检验</button>
                <button class="act" :disabled="o.status !== '待检验'" @click="transfer(o.id, '已完成')">完成</button>
                <button class="act" @click="transfer(o.id, '已取消')">取消</button>
              </div>
              <div class="note">投产会走齐套校验，缺料或齐套率不足会被拦下。</div>
            </div>
          </div>
          <div v-if="!(byLane[p] || []).length" class="lane-empty">本泳道暂无工单</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.lane { margin-bottom: 10px; border: 1px solid var(--line); border-radius: 9px; overflow: hidden; }
.lane-head {
  display: flex; align-items: center; gap: 10px;
  padding: 6px 10px; background: var(--primary-wash);
}
.lane-name { font-weight: 800; font-size: 12px; padding: 2px 10px; border-radius: 10px; color: #fff; }
.lane-name.p-高 { background: #c62828; }
.lane-name.p-中 { background: var(--primary-soft); }
.lane-name.p-低 { background: #8d9a9c; }
.lane-count { font-size: 12px; color: var(--primary-deep); font-weight: 700; }

.lane-body { display: flex; gap: 8px; flex-wrap: wrap; padding: 9px; background: #fff; min-height: 60px; }
.lane-empty { font-size: 12px; color: var(--muted); padding: 8px; }

.wo-card {
  width: 250px;
  border: 1px solid var(--line); border-radius: 9px;
  padding: 9px 11px; cursor: pointer; background: #fff;
}
.wo-card:hover { border-color: var(--primary-soft); }
.wo-card.open { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.wo-card.late { border-color: #ef9a9a; background: #fff6f6; }

.wo-top { display: flex; align-items: center; gap: 6px; }
.wo-no { font-weight: 800; color: var(--primary-deep); font-size: 13px; }
.wo-model { font-size: 11px; color: var(--muted); }
.wo-tag { margin-left: auto; font-size: 10px; padding: 1px 7px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.wo-lc { font-size: 10px; padding: 1px 7px; border-radius: 9px; background: #e8f5e9; color: #2e7d32; font-weight: 700; }
.wo-lc.blocked { background: #fdecea; color: #c62828; }

.lc-line {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 11px; color: #2e7d32; background: #eef7ee;
  border: 1px solid #c8e6c9; border-radius: 7px; padding: 6px 9px; margin-bottom: 8px;
}
.lc-line.blocked { color: #a32b20; background: #fdecea; border-color: #f5c6c2; }

.wo-bar { height: 6px; background: #eef0fa; border-radius: 3px; margin: 7px 0 5px; overflow: hidden; }
.wo-bar-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--primary-soft)); }

.wo-meta { font-size: 11px; color: var(--muted); }
.wo-meta .late { color: #c62828; font-weight: 700; }

.wo-detail { margin-top: 9px; padding-top: 9px; border-top: 1px dashed var(--line); }
.op-row { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.op-label { font-size: 12px; color: var(--muted); }
.note { font-size: 11px; color: var(--muted); margin-top: 6px; }
</style>
