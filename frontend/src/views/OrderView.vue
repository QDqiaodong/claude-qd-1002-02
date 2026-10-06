<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

const routeQuery = computed(() => router.currentRoute.value.query || {})
const orders = ref([])
const lcMap = ref({})
const error = ref('')
const tip = ref('')
const openId = ref(null)
const reportQty = ref(50)
const splitDrafts = reactive({})
const batchDrafts = reactive({})

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

function toggle(o) {
  openId.value = openId.value === o.id ? null : o.id
  if (openId.value === o.id && !o.batchEnabled) initSplitDraft(o)
}

function goLineChange(id) {
  router.push('/line-change?order=' + id)
}

function initSplitDraft(o) {
  if (splitDrafts[o.id]) return
  const remaining = Math.max(0, (o.planQty || 0) - (o.doneQty || 0))
  const first = remaining ? Math.ceil(remaining / 2) : 0
  splitDrafts[o.id] = {
    operator: '计划员',
    rows: [{ planQty: first }, { planQty: remaining - first }]
  }
}

function addSplitRow(o) {
  splitDrafts[o.id].rows.push({ planQty: 0 })
}

function removeSplitRow(o, i) {
  splitDrafts[o.id].rows.splice(i, 1)
}

async function split(o) {
  error.value = ''; tip.value = ''
  try {
    const draft = splitDrafts[o.id]
    const batches = draft.rows
      .map((r, i) => ({ planQty: Number(r.planQty), deliverySeq: i + 1 }))
      .filter((r) => Number.isFinite(r.planQty))
    await api.splitBatches(o.id, { operator: draft.operator, batches })
    tip.value = '分批方案已保存，母工单数量已按批次统一汇总'
    await load()
  } catch (e) { error.value = e.message }
}

function draftBatch(b) {
  if (!batchDrafts[b.id]) {
    batchDrafts[b.id] = {
      reportQty: 50,
      planQty: b.planQty,
      deliverySeq: b.deliverySeq,
      recycleToBatchId: '',
      cancelTarget: ''
    }
  }
  return batchDrafts[b.id]
}

function pendingSiblings(o, batch) {
  return (o.batches || []).filter((b) => b.status === '待投产' && b.id !== batch.id && !b.readonlyHistory)
}

async function release(o, b) {
  error.value = ''; tip.value = ''
  try { await api.releaseBatch(b.id); tip.value = `批次 ${b.batchNo} 已投产`; await load() }
  catch (e) { error.value = e.message }
}

async function releaseOrder(id) {
  error.value = ''; tip.value = ''
  try { await api.releaseOrder(id); tip.value = '已投产，SMT 五道工序已铺开'; await load() }
  catch (e) { error.value = e.message }
}

async function reportBatch(b) {
  error.value = ''; tip.value = ''
  try {
    const qty = Number(draftBatch(b).reportQty)
    await api.reportBatch(b.id, qty)
    tip.value = `批次 ${b.batchNo} 已报工 ${qty} 板`
    await load()
  } catch (e) { error.value = e.message }
}

async function reportOrder(id) {
  error.value = ''; tip.value = ''
  try {
    await api.reportOrder(id, Number(reportQty.value))
    tip.value = `已报工 ${reportQty.value} 板`
    await load()
  } catch (e) { error.value = e.message }
}

async function transferBatch(b, target) {
  error.value = ''; tip.value = ''
  try { await api.transferBatch(b.id, target); tip.value = `批次 ${b.batchNo} 已转为「${target}」`; await load() }
  catch (e) { error.value = e.message }
}

async function transferOrder(id, target) {
  error.value = ''; tip.value = ''
  try { await api.transferOrder(id, target); tip.value = `已转为「${target}」`; await load() }
  catch (e) { error.value = e.message }
}

async function adjustBatch(b) {
  error.value = ''; tip.value = ''
  const d = draftBatch(b)
  try {
    await api.adjustBatch(b.id, {
      planQty: Number(d.planQty),
      deliverySeq: Number(d.deliverySeq),
      operator: '计划员',
      reason: '页面调整未投产批次'
    })
    tip.value = `批次 ${b.batchNo} 已调整`
    await load()
  } catch (e) { error.value = e.message }
}

async function removeBatch(o, b) {
  error.value = ''; tip.value = ''
  const targetId = Number(draftBatch(b).recycleToBatchId)
  try {
    await api.deleteBatch(b.id, targetId, '计划员')
    tip.value = `批次 ${b.batchNo} 已删除，数量已回收`
    await load()
  } catch (e) { error.value = e.message }
}

async function cancelBatch(o, b) {
  error.value = ''; tip.value = ''
  const target = draftBatch(b).cancelTarget
  try {
    const body = { operator: '计划员', reason: '计划员在工单卡片取消批次' }
    if (target === '__REDUCTION__') body.confirmReduction = true
    else { body.recycleToBatchId = Number(target) }
    await api.cancelBatch(b.id, body)
    tip.value = `批次 ${b.batchNo} 已取消`
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
        <span class="panel-desc">卡片展示母工单与制造批次树；数量守恒通过后才可保存分批方案</span>
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
        <div class="lane-body wide">
          <div
            v-for="o in (byLane[p] || [])"
            :key="o.id"
            class="wo-card"
            :class="{ open: openId === o.id, late: o.daysToDue !== null && o.daysToDue < 0 }"
            @click="toggle(o)"
          >
            <div class="wo-top">
              <span class="wo-no">{{ o.orderNo }}</span>
              <span class="wo-model">{{ o.productModel }}</span>
              <span v-if="o.batchEnabled" class="batch-badge">分批 {{ o.batches.length }}</span>
              <span v-if="lcMap[o.id]" class="wo-lc" :class="{ blocked: lcMap[o.id].blocked }">
                换线V{{ lcMap[o.id].versionNo }}·{{ lcMap[o.id].status }}
              </span>
              <span class="wo-tag">{{ o.status }}</span>
            </div>
            <div class="wo-bar">
              <div class="wo-bar-fill" :style="{ width: Math.min(100, o.progressRate) + '%' }"></div>
            </div>
            <div class="wo-meta">
              母单汇总 {{ o.doneQty }} / {{ o.planQty }} 板 · {{ o.progressRate }}%
              <span v-if="o.daysToDue !== null" :class="{ late: o.daysToDue < 0 }">
                · 距交期 {{ o.daysToDue }} 天
              </span>
            </div>

            <div v-if="openId === o.id" class="wo-detail" @click.stop>
              <div v-if="o.batchEnabled" class="batch-tree">
                <div v-for="b in o.batches" :key="b.id" class="batch-node" :class="{ history: b.readonlyHistory, canceled: b.status === '已取消' }">
                  <div class="batch-head">
                    <span class="tree-dash">└</span>
                    <b>{{ b.batchNo }}</b>
                    <em>#{{ b.deliverySeq || '历史' }}</em>
                    <span class="b-status">{{ b.status }}</span>
                    <span v-if="b.readonlyHistory" class="readonly">只读历史</span>
                  </div>
                  <div class="batch-metrics">
                    <span>计划 {{ b.planQty }}</span>
                    <span>报工 {{ b.reportedQty }}</span>
                    <span>送检 {{ b.inspectedQty }}</span>
                    <span>完成 {{ b.completedQty }}</span>
                    <span :class="{ danger: b.qtyDifference !== 0 }">差异 {{ b.qtyDifference }}</span>
                  </div>
                  <div class="batch-progress">
                    <span>齐套率 {{ b.kitRate }}% · {{ b.kitOk ? '可投产' : '缺料' }}</span>
                    <span>工序 {{ b.stepDoneCount }}/{{ b.stepTotalCount || 5 }}</span>
                    <span>进度 {{ b.progressRate }}%</span>
                  </div>
                  <div v-if="b.events && b.events.length" class="event-line">
                    <div class="event-title">调整记录（{{ b.events.length }}）</div>
                    <div v-for="e in b.events" :key="e.id" class="event-item">
                      {{ e.createdAt?.replace('T', ' ').slice(0, 16) }} · {{ e.operator }} · {{ e.action }} · {{ e.detail }}
                    </div>
                  </div>

                  <div v-if="!b.readonlyHistory && b.status !== '已取消'" class="batch-ops">
                    <template v-if="b.status === '待投产'">
                      <button class="act sm primary" @click="release(o, b)">投产</button>
                      <input v-model.number="draftBatch(b).planQty" type="number" min="1" class="fld mini" />
                      <input v-model.number="draftBatch(b).deliverySeq" type="number" min="1" class="fld mini seq" title="交付顺序" />
                      <button class="act sm" @click="adjustBatch(b)">保存调整</button>
                      <select v-model="draftBatch(b).recycleToBatchId" class="fld mini">
                        <option value="" disabled>删除回收</option>
                        <option v-for="t in pendingSiblings(o, b)" :key="t.id" :value="t.id">{{ t.batchNo }}</option>
                      </select>
                      <button class="act sm danger" :disabled="!draftBatch(b).recycleToBatchId" @click="removeBatch(o, b)">删除</button>
                      <select v-model="draftBatch(b).cancelTarget" class="fld mini">
                        <option value="" disabled>取消去向</option>
                        <option v-for="t in pendingSiblings(o, b)" :key="t.id" :value="t.id">回收至{{ t.batchNo }}</option>
                        <option value="__REDUCTION__">计划员确认减产</option>
                      </select>
                      <button class="act sm danger" :disabled="!draftBatch(b).cancelTarget" @click="cancelBatch(o, b)">取消</button>
                    </template>
                    <template v-else>
                      <input v-model.number="draftBatch(b).reportQty" type="number" min="1" class="fld mini" />
                      <button class="act sm" :disabled="b.status !== '贴片中'" @click="reportBatch(b)">报工</button>
                      <button class="act sm" :disabled="b.status !== '贴片中'" @click="transferBatch(b, '待检验')">送检</button>
                      <button class="act sm" :disabled="b.status !== '待检验'" @click="transferBatch(b, '已完成')">完成</button>
                    </template>
                  </div>
                </div>
              </div>

              <div v-else-if="o.status === '待投产'" class="split-box">
                <div class="split-title">首次启用分批：旧完成数量将自动进入只读历史批次</div>
                <div v-for="(row, i) in splitDrafts[o.id]?.rows || []" :key="i" class="split-row">
                  <span>批次 {{ i + 1 }}</span>
                  <input v-model.number="row.planQty" type="number" min="1" class="fld n" />
                  <button class="act sm danger" @click="removeSplitRow(o, i)">移除</button>
                </div>
                <div class="op-row">
                  <button class="act sm" @click="addSplitRow(o)">增加批次</button>
                  <input v-model="splitDrafts[o.id].operator" class="fld" style="width:96px" />
                  <button class="act primary" @click="split(o)">保存分批方案</button>
                </div>
                <div class="note">有效批次计划数量之和必须等于母工单计划板数；空批次、不足或超额均不能保存。</div>
              </div>

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

              <div v-if="!o.batchEnabled" class="op-row">
                <button class="act primary" :disabled="o.status !== '待投产'" @click="releaseOrder(o.id)">整单投产</button>
                <span class="op-label">报工</span>
                <input type="number" min="1" v-model.number="reportQty" class="fld n" />
                <button class="act" :disabled="o.status !== '贴片中'" @click="reportOrder(o.id)">登记</button>
                <button class="act" :disabled="o.status !== '贴片中'" @click="transferOrder(o.id, '待检验')">转待检验</button>
                <button class="act" :disabled="o.status !== '待检验'" @click="transferOrder(o.id, '已完成')">完成</button>
                <button class="act" @click="transferOrder(o.id, '已取消')">取消</button>
              </div>
              <div v-if="!o.batchEnabled" class="note">历史工单继续按原流程运行；待投产工单也可先保存分批方案。</div>
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
  width: 270px;
  border: 1px solid var(--line); border-radius: 9px;
  padding: 9px 11px; cursor: pointer; background: #fff;
}
.wo-card.open { width: 560px; }
.wo-card:hover { border-color: var(--primary-soft); }
.wo-card.open { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.wo-card.late { border-color: #ef9a9a; background: #fff6f6; }

.wo-top { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.wo-no { font-weight: 800; color: var(--primary-deep); font-size: 13px; }
.wo-model { font-size: 11px; color: var(--muted); }
.wo-tag { margin-left: auto; font-size: 10px; padding: 1px 7px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.batch-badge { font-size: 10px; padding: 1px 7px; border-radius: 9px; background: #ede7f6; color: #4527a0; font-weight: 800; }
.wo-lc { font-size: 10px; padding: 1px 7px; border-radius: 9px; background: #e8f5e9; color: #2e7d32; font-weight: 700; }
.wo-lc.blocked { background: #fdecea; color: #c62828; }

.lc-line {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  font-size: 11px; color: #2e7d32; background: #eef7ee;
  border: 1px solid #c8e6c9; border-radius: 7px; padding: 6px 9px; margin: 8px 0;
}
.lc-line.blocked { color: #a32b20; background: #fdecea; border-color: #f5c6c2; }

.wo-bar { height: 6px; background: #eef0fa; border-radius: 3px; margin: 7px 0 5px; overflow: hidden; }
.wo-bar-fill { height: 100%; background: linear-gradient(90deg, var(--primary), var(--primary-soft)); }
.wo-meta { font-size: 11px; color: var(--muted); }
.wo-meta .late { color: #c62828; font-weight: 700; }
.wo-detail { margin-top: 9px; padding-top: 9px; border-top: 1px dashed var(--line); }
.op-row { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; margin-top: 6px; }
.op-label { font-size: 12px; color: var(--muted); }
.note { font-size: 11px; color: var(--muted); margin-top: 6px; }

.batch-tree { display: flex; flex-direction: column; gap: 7px; margin-bottom: 8px; }
.batch-node { border: 1px solid #d7dbef; border-radius: 8px; padding: 7px 9px; background: #fbfcff; }
.batch-node.history { background: #f5f5f5; border-color: #d0d0d0; }
.batch-node.canceled { opacity: .72; background: #fafafa; }
.batch-head { display: flex; align-items: center; gap: 6px; font-size: 12px; }
.tree-dash { color: var(--primary-soft); font-weight: 900; }
.batch-head b { color: var(--primary-deep); }
.batch-head em { font-style: normal; color: var(--muted); font-size: 11px; }
.b-status { margin-left: auto; font-size: 10px; font-weight: 800; color: var(--primary-deep); background: var(--primary-wash); padding: 1px 7px; border-radius: 9px; }
.readonly { font-size: 10px; color: #616161; background: #eeeeee; padding: 1px 6px; border-radius: 9px; }
.batch-metrics, .batch-progress { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 5px; font-size: 11px; color: #424242; }
.batch-progress { color: var(--muted); }
.danger { color: #c62828; font-weight: 800; }
.event-line { font-size: 10px; color: #616161; background: #f5f6fb; border-radius: 6px; padding: 4px 6px; margin-top: 5px; }
.event-title { font-weight: 800; color: var(--primary-deep); margin-bottom: 2px; }
.event-item { margin-top: 1px; line-height: 1.35; }
.batch-ops { display: flex; gap: 5px; align-items: center; flex-wrap: wrap; margin-top: 7px; }
.mini { width: 72px; padding: 3px 6px; font-size: 11px; }
.mini.seq { width: 54px; }
.act.danger { border-color: #ef9a9a; color: #b71c1c; }
.act.danger:disabled { opacity: .45; }

.split-box { border: 1px dashed var(--primary-soft); background: #f8f9ff; border-radius: 8px; padding: 8px; margin-bottom: 8px; }
.split-title { font-size: 12px; font-weight: 800; color: var(--primary-deep); margin-bottom: 6px; }
.split-row { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; font-size: 12px; color: var(--muted); }
</style>
