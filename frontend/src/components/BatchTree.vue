<script setup>
import { computed, ref } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 工单卡片里的「批次树」：母工单 → 只读历史批次 + 各制造批次。
// 每批展示齐套率环、五道工序进度、数量差异（计划/报工/送检/完成）与调整记录。
// 数量口径全部以后端批次汇总为准：方案不守恒会整份拒绝，前端只做提示与禁用。
// ------------------------------------------------------------------

const props = defineProps({
  order: { type: Object, required: true }
})
const emit = defineEmits(['changed', 'error'])

const ctx = ref(null)
const loading = ref(false)
const operator = ref('计划员')
const editing = ref(false)
const planRows = ref([])
const actQty = ref(20)
const cancelFor = ref(null)
const cancelMode = ref('reclaim') // reclaim / reduce
const reclaimTarget = ref(null)
const showEventsFor = ref(null)

const STEPS = ['上料', '贴片', '回流焊', 'AOI', '目检']

const regularBatches = computed(() =>
  (ctx.value?.batches || []).filter((b) => !b.readOnly))

async function load() {
  loading.value = true
  try {
    ctx.value = await api.batchContext(props.order.id)
  } catch (e) {
    emit('error', e.message)
  } finally {
    loading.value = false
  }
}

// 显式加载（卡片首次展开时由父组件触发，避免一屏卡片全拉详情）
defineExpose({ load })

function beginEdit() {
  // 方案行：已开工批次也带上（数量只读），未投产批次可改/删
  planRows.value = regularBatches.value.map((b) => ({
    id: b.id,
    planQty: b.planQty,
    started: b.started,
    batchNo: b.batchNo,
    _remove: false
  }))
  // 首次启用时没有批次，预填两行方便拆分
  if (!planRows.value.length) {
    planRows.value = [
      { id: null, planQty: Math.ceil(editableBaseQty() / 2), started: false },
      { id: null, planQty: Math.floor(editableBaseQty() / 2), started: false }
    ]
  }
  editing.value = true
}

// 可拆数量：母工单计划 - 历史冻结 - 已减产
function editableBaseQty() {
  if (!ctx.value) return props.order.planQty
  const hist = (ctx.value.batches || []).find((b) => b.readOnly)
  return ctx.value.orderPlanQty - (hist ? hist.planQty : 0) - ctx.value.reduceSum
}

const planTotal = computed(() =>
  planRows.value.filter((r) => !r._remove).reduce((s, r) => s + (Number(r.planQty) || 0), 0))

const planConserved = computed(() => planTotal.value === editableBaseQty())

function addRow() {
  planRows.value.push({ id: null, planQty: 0, started: false })
}

async function savePlan() {
  emit('error', '')
  const lines = planRows.value
    .filter((r) => !r._remove)
    .map((r) => ({ id: r.id, planQty: Number(r.planQty) }))
  if (!lines.length) {
    emit('error', '至少保留一个批次')
    return
  }
  if (lines.some((l) => !l.planQty || l.planQty <= 0)) {
    emit('error', '存在空批次（计划数量必须大于 0），整份方案不会保存')
    return
  }
  if (!planConserved.value) {
    emit('error', `数量不守恒：合计 ${planTotal.value}，必须等于可拆数量 ${editableBaseQty()}`)
    return
  }
  try {
    await api.saveBatchPlan(props.order.id, { operator: operator.value, batches: lines })
    editing.value = false
    await load()
    emit('changed')
  } catch (e) { emit('error', e.message) }
}

async function act(fn, ok) {
  emit('error', '')
  try {
    await fn()
    await load()
    emit('changed')
    if (ok) emit('error', '')
  } catch (e) { emit('error', e.message) }
}

function release(b) { act(() => api.releaseBatch(b.id)) }
function report(b) {
  act(() => api.reportBatch(b.id, Number(actQty.value), operator.value))
}
function inspect(b) {
  act(() => api.inspectBatch(b.id, Number(actQty.value), operator.value))
}
function complete(b) {
  act(() => api.completeBatchQty(b.id, Number(actQty.value), operator.value))
}
function transfer(b, target) { act(() => api.transferBatch(b.id, target)) }

function openCancel(b) {
  cancelFor.value = b
  cancelMode.value = 'reclaim'
  reclaimTarget.value = (ctx.value.batches || [])
    .find((x) => !x.readOnly && !x.started && x.id !== b.id)?.id || null
}

async function confirmCancel() {
  const b = cancelFor.value
  try {
    await api.cancelBatch(b.id, {
      reclaimToBatchId: cancelMode.value === 'reclaim' ? reclaimTarget.value : null,
      confirmReduce: cancelMode.value === 'reduce',
      operator: operator.value
    })
    cancelFor.value = null
    await load()
    emit('changed')
  } catch (e) { emit('error', e.message) }
}

function kitColor(b) { return b.kitOk ? '#2e7d32' : '#c62828' }
function statusTag(s) { return { color: s === '已取消' ? '#90a4ae' : 'var(--primary-deep)' } }
</script>

<template>
  <div class="batch-tree" v-if="ctx">
    <!-- 母子汇总条 -->
    <div class="bt-summary" :class="{ bad: !ctx.conserved }">
      <span class="bt-title">分批生产</span>
      <span>有效批次计划 <b>{{ ctx.planSum }}</b></span>
      <span v-if="ctx.reduceSum">已减产 <b class="warn">{{ ctx.reduceSum }}</b></span>
      <span>母工单计划 <b>{{ ctx.orderPlanQty }}</b></span>
      <span>汇总完成 <b>{{ ctx.doneSum }}</b> / {{ ctx.orderDoneQty }}</span>
      <span class="conserve" :class="{ ok: ctx.conserved }">
        {{ ctx.conserved ? '数量守恒 ✓' : '数量不守恒 ✗' }}
      </span>
      <span v-if="!ctx.allFinished" class="fin-note">全部批次完成后母工单才能完成</span>
      <button class="act sm" style="margin-left:auto" @click="beginEdit">
        {{ regularBatches.length ? '调整批次' : '启用分批' }}
      </button>
    </div>

    <!-- 历史只读批次 -->
    <template v-for="h in ctx.batches.filter(b => b.readOnly)" :key="'h' + h.id">
      <div class="bt-row history">
        <div class="bt-head">
          <span class="bt-no">{{ h.batchNo }}</span>
          <span class="bt-tag ro">只读历史批次</span>
          <span class="bt-tag">{{ h.status }}</span>
        </div>
        <div class="bt-qty">
          计划/冻结完成 {{ h.planQty }} 板（启用分批前母工单完成数量，不与新批次重复累计）
        </div>
      </div>
    </template>

    <!-- 制造批次树 -->
    <div v-for="b in regularBatches" :key="b.id" class="bt-row" :class="{ cancelled: b.status === '已取消' }">
      <div class="bt-head">
        <span class="bt-seq">#{{ b.seqNo }}</span>
        <span class="bt-no">{{ b.batchNo }}</span>
        <span class="bt-tag" :style="statusTag(b.status)">{{ b.status }}</span>
        <span class="bt-kit" :style="{ color: kitColor(b) }">齐套 {{ b.kitRate }}%</span>
        <span class="bt-progress">{{ b.stepDone }}/{{ b.stepTotal }} 工序</span>
        <span class="bt-pct">{{ b.progressRate }}%</span>
        <button class="linkbtn" @click="showEventsFor = showEventsFor === b.id ? null : b.id">
          调整记录
        </button>
      </div>

      <!-- 数量差异：计划 / 报工 / 送检 / 完成 / 欠产 -->
      <div class="bt-qty">
        <span>计划 <b>{{ b.planQty }}</b></span>
        <span>报工 <b>{{ b.reportedQty }}</b></span>
        <span>送检 <b>{{ b.inspectedQty }}</b></span>
        <span>完成 <b>{{ b.doneQty }}</b></span>
        <span v-if="b.reduceQty" class="warn">减产 {{ b.reduceQty }}</span>
        <span v-else-if="b.status !== '已取消'" :class="{ gap: b.gapQty > 0 }">
          {{ b.gapQty > 0 ? '欠产 ' + b.gapQty : '已齐量' }}
        </span>
      </div>

      <!-- 五道工序迷你进度 -->
      <div class="bt-steps">
        <span v-for="(s, i) in b.stepStatus" :key="i" class="mini-step" :class="'ms-' + s">
          {{ STEPS[i] }}·{{ s }}
        </span>
      </div>

      <!-- 操作：按状态与开工情况禁用 -->
      <div v-if="b.status !== '已取消'" class="bt-ops">
        <button class="act sm primary" :disabled="b.status !== '待投产' || b.readOnly" @click="release(b)">投产</button>
        <input type="number" min="1" v-model.number="actQty" class="fld n sm-in" />
        <button class="act sm" :disabled="b.status !== '贴片中'" @click="report(b)">报工</button>
        <button class="act sm" :disabled="b.status === '贴片中' ? b.reportedQty <= b.inspectedQty : true" @click="inspect(b)">送检</button>
        <button class="act sm" :disabled="b.status !== '待检验' && b.inspectedQty <= b.doneQty" @click="complete(b)">完成入库</button>
        <button class="act sm" :disabled="b.status !== '贴片中'" @click="transfer(b, '待检验')">转待检验</button>
        <button class="act sm" :disabled="b.status !== '待检验' || b.doneQty !== b.planQty" @click="transfer(b, '已完成')">批次完成</button>
        <button class="act sm danger" :disabled="b.readOnly || b.status === '已完成' || b.unreportedQty <= 0" @click="openCancel(b)">
          取消
        </button>
      </div>

      <!-- 取消：回收或减产二选一 -->
      <div v-if="cancelFor && cancelFor.id === b.id" class="cancel-box">
        <div>未产 {{ b.unreportedQty }} 板，已报工/送检/完成数量冻结在本批不挪走：</div>
        <label>
          <input type="radio" v-model="cancelMode" value="reclaim" /> 回收到未投产批次
          <select v-model="reclaimTarget" class="fld sm-in" :disabled="cancelMode !== 'reclaim'">
            <option v-for="t in ctx.batches.filter(x => !x.readOnly && !x.started && x.id !== b.id)"
                    :key="t.id" :value="t.id">{{ t.batchNo }}（计划 {{ t.planQty }}）</option>
          </select>
        </label>
        <label>
          <input type="radio" v-model="cancelMode" value="reduce" /> 计划员确认减产（母工单计划调减 {{ b.unreportedQty }}）
        </label>
        <div class="cancel-btns">
          <button class="act sm primary" @click="confirmCancel">确认取消</button>
          <button class="act sm" @click="cancelFor = null">再想想</button>
        </div>
      </div>

      <!-- 调整记录时间线 -->
      <div v-if="showEventsFor === b.id" class="bt-events">
        <div v-if="!b.events.length" class="ev-empty">暂无调整记录</div>
        <div v-for="(e, i) in b.events" :key="i" class="ev">
          <span class="ev-a">{{ e.action }}</span>
          <span class="ev-d">{{ e.detail }}</span>
          <span class="ev-who">{{ e.actor }}</span>
        </div>
      </div>
    </div>

    <!-- 方案编辑 -->
    <div v-if="editing" class="plan-edit">
      <div class="pe-head">
        <b>{{ regularBatches.length ? '调整分批方案' : '首次启用分批' }}</b>
        <span>可拆数量 {{ editableBaseQty() }} 板；已开工批次数量与顺序冻结，只能改未投产批次</span>
      </div>
      <div v-for="(r, i) in planRows" :key="i" class="pe-row" :class="{ remove: r._remove }">
        <span class="pe-seq">交付 {{ i + 1 }}</span>
        <span v-if="r.batchNo" class="pe-no">{{ r.batchNo }}</span>
        <input type="number" min="1" v-model.number="r.planQty" class="fld n" :disabled="r.started || r._remove" />
        <span class="pe-state">{{ r.started ? '已开工·数量冻结' : '未投产·可调整' }}</span>
        <button class="act sm danger" :disabled="r.started" @click="r._remove = !r._remove">
          {{ r._remove ? '保留' : '删除' }}
        </button>
      </div>
      <button class="act sm" @click="addRow">＋ 增加批次</button>
      <div class="pe-foot">
        <span :class="{ ok: planConserved, bad: !planConserved }">
          合计 {{ planTotal }} / {{ editableBaseQty }} {{ planConserved ? '守恒' : '不守恒' }}
        </span>
        <button class="act primary" :disabled="!planConserved" @click="savePlan">保存整份方案</button>
        <button class="act" @click="editing = false">取消编辑</button>
      </div>
      <div class="pe-tip">
        首次启用时母工单原有完成 {{ props.order.doneQty }} 板会落到只读历史批次冻结，不与新批次重复累计。
      </div>
    </div>
  </div>
</template>

<style scoped>
.batch-tree { margin-top: 8px; border: 1px solid var(--line); border-radius: 8px; padding: 8px; background: #fafbff; }
.bt-summary { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--muted); padding-bottom: 7px; border-bottom: 1px dashed var(--line); margin-bottom: 7px; }
.bt-summary b { color: var(--primary-deep); }
.bt-title { font-weight: 800; color: var(--primary-deep); font-size: 12px; }
.conserve { font-weight: 800; color: #c62828; }
.conserve.ok { color: #2e7d32; }
.bt-summary.bad { background: #fff6f6; border-radius: 6px; }
.warn { color: #e65100; font-weight: 700; }
.fin-note { color: #c62828; }

.bt-row { border: 1px solid var(--line); border-radius: 7px; padding: 7px 9px; margin-bottom: 6px; background: #fff; }
.bt-row.cancelled { opacity: 0.62; background: #f4f5f7; }
.bt-row.history { background: #f1f3fa; border-style: dashed; }
.bt-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.bt-seq { font-size: 10px; color: #fff; background: #8d9a9c; border-radius: 8px; padding: 0 7px; }
.bt-no { font-weight: 800; color: var(--primary-deep); font-size: 12px; }
.bt-tag { font-size: 10px; padding: 1px 7px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.bt-tag.ro { background: #e8eaf6; color: #5c6bc0; }
.bt-kit { font-size: 11px; font-weight: 800; }
.bt-progress { font-size: 11px; color: var(--muted); }
.bt-pct { font-size: 11px; font-weight: 800; color: var(--primary-deep); }
.linkbtn { margin-left: auto; border: none; background: none; color: var(--primary-soft); font-size: 11px; cursor: pointer; text-decoration: underline; }

.bt-qty { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--muted); margin: 5px 0; }
.bt-qty b { color: var(--ink); }
.bt-qty .gap { color: #c62828; font-weight: 700; }

.bt-steps { display: flex; gap: 4px; flex-wrap: wrap; margin-bottom: 6px; }
.mini-step { font-size: 10px; padding: 1px 6px; border-radius: 7px; background: #eceff8; color: var(--muted); }
.ms-已完成 { background: #e3f6e8; color: #2e7d32; font-weight: 700; }
.ms-进行中 { background: #e8eaf6; color: var(--primary-deep); font-weight: 700; }
.ms-返修中 { background: #ffe0b2; color: #e65100; font-weight: 700; }

.bt-ops { display: flex; gap: 5px; align-items: center; flex-wrap: wrap; }
.sm-in { width: 64px; padding: 3px 6px; font-size: 12px; }
.act.danger { border-color: #ef9a9a; color: #b71c1c; }

.cancel-box { margin-top: 7px; border: 1px solid #ffcc80; background: #fffaf0; border-radius: 7px; padding: 8px; font-size: 12px; display: flex; flex-direction: column; gap: 6px; }
.cancel-btns { display: flex; gap: 6px; }

.bt-events { margin-top: 6px; border-top: 1px dashed var(--line); padding-top: 6px; display: flex; flex-direction: column; gap: 3px; }
.ev { display: flex; gap: 8px; font-size: 11px; }
.ev-a { font-weight: 800; color: var(--primary-deep); min-width: 52px; }
.ev-d { color: var(--ink); flex: 1; }
.ev-who { color: var(--muted); }
.ev-empty { font-size: 11px; color: var(--muted); }

.plan-edit { border: 1px solid var(--primary-soft); border-radius: 8px; padding: 9px; background: #fff; }
.pe-head { display: flex; flex-direction: column; gap: 2px; margin-bottom: 7px; }
.pe-head span { font-size: 11px; color: var(--muted); }
.pe-row { display: flex; align-items: center; gap: 8px; margin-bottom: 5px; }
.pe-row.remove { opacity: 0.4; }
.pe-seq { font-size: 11px; color: var(--muted); min-width: 48px; }
.pe-no { font-size: 11px; font-weight: 700; color: var(--primary-deep); }
.pe-state { font-size: 10px; color: var(--muted); }
.pe-foot { display: flex; align-items: center; gap: 10px; margin-top: 8px; font-size: 12px; font-weight: 800; }
.pe-foot .ok { color: #2e7d32; }
.pe-foot .bad { color: #c62828; }
.pe-tip { font-size: 11px; color: var(--muted); margin-top: 6px; }
.n { width: 90px; }
</style>
