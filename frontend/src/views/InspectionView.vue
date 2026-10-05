<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：缺陷点阵 + 返修闭环。
// 质检员提交不合格（返修）结论后，系统以同一质检记录幂等创建/复用返修任务，
// 对应工序游标自动置「返修中」；返修工在闭环面板报完工后转「待复验」，
// 只能由质检员复验：通过 → 工序已完成闭环；不通过 → 返修轮次 +1 退回返修。
// 提交幂等号在点击时生成并保留到成功为止，双击 / 网络重试不会产生重复返修任务。
// ------------------------------------------------------------------

const orders = ref([])
const inspections = ref([])
const repairTasks = ref([])
const error = ref('')
const tip = ref('')
const MAX_POINTS = 12
const submitting = ref(false)
const form = ref({ orderId: null, stepName: 'AOI', defectType: '偏移', defectPoints: 0, sampleQty: 40, inspector: '孙悦' })
const DEFECTS = ['少件', '偏移', '虚焊', '连锡', '反向']

// 返修工署名 / 复验署名与备注
const repairBy = ref('李返修')
const recheckInspector = ref('孙悦')
const busyId = ref(null)

// inspectionId -> 返修任务（由任务列表派生，保证卡片、面板、详情口径一致）
const taskByInspection = computed(() => {
  const m = new Map()
  for (const t of repairTasks.value) m.set(t.inspection?.id ?? t.inspectionId, t)
  return m
})

const scrapped = computed(() => inspections.value.filter((i) => i.verdict === '判废').length)

// 待返修（返修工处理）/ 待复验（质检员处理）分泳道
const todoRepair = computed(() => repairTasks.value.filter((t) => t.status === '待返修'))
const todoRecheck = computed(() => repairTasks.value.filter((t) => t.status === '待复验'))

async function load() {
  error.value = ''
  try {
    const [os, is, ts] = await Promise.all([
      api.listOrders(),
      api.listInspections(),
      api.listRepairTasks()
    ])
    orders.value = os
    inspections.value = is
    repairTasks.value = ts
    if (!form.value.orderId && orders.value.length) form.value.orderId = orders.value[0].id
  } catch (e) { error.value = e.message }
}

async function create() {
  if (submitting.value) return // 防重复点击：在途期间直接忽略
  error.value = ''; tip.value = ''
  // 幂等号在成功前一直保留：网络超时重试、双击重放都返回同一条记录/同一个返修任务
  const requestId = form.value.requestId || (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random())
  form.value.requestId = requestId
  submitting.value = true
  try {
    const payload = { ...form.value, order: { id: form.value.orderId }, requestId }
    const saved = await api.createInspection(payload)
    tip.value = saved.verdict === '返修'
      ? `已登记质检，结论「返修」：返修任务已建立，工序「${saved.stepName}」已置为返修中`
      : `已登记质检，结论「${saved.verdict}」`
    form.value.requestId = null
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    submitting.value = false
  }
}

// 返修工完工上报（旧「闭环」勾选位置改为按当前任务状态推进）
async function completeRepair(task) {
  error.value = ''; tip.value = ''
  busyId.value = task.id
  try {
    await api.completeRepairTask(task.id, { repairBy: repairBy.value, repairNote: '' })
    tip.value = `第 ${task.roundNo} 轮返修已完工，等待质检员复验`
    await load()
  } catch (e) { error.value = e.message } finally { busyId.value = null }
}

// 质检员复验：通过 / 不通过
async function recheck(task, result) {
  error.value = ''; tip.value = ''
  busyId.value = task.id
  try {
    await api.recheckRepairTask(task.id, { inspector: recheckInspector.value, result, note: '' })
    tip.value = result === '合格'
      ? `复验通过：第 ${task.roundNo} 轮返修闭环，工序「${task.stepName}」已置为已完成`
      : `复验不通过：返修轮次已递增，工序「${task.stepName}」继续返修中`
    await load()
  } catch (e) { error.value = e.message } finally { busyId.value = null }
}

function points(n) {
  const v = Math.min(Number(n) || 0, MAX_POINTS + 3)
  return Array.from({ length: v }, (_, i) => i)
}

function orderNoOf(id) {
  return orders.value.find((o) => o.id === id)?.orderNo || `#${id}`
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">质检登记</span>
        <span class="panel-desc">不良点数超过 {{ MAX_POINTS }} 点自动判废，有缺陷但未超判返修并自动派返修任务</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="form-row">
        <select v-model="form.orderId" class="fld">
          <option v-for="o in orders" :key="o.id" :value="o.id">{{ o.orderNo }}</option>
        </select>
        <select v-model="form.stepName" class="fld">
          <option>上料</option><option>贴片</option><option>回流焊</option><option>AOI</option><option>目检</option>
        </select>
        <select v-model="form.defectType" class="fld">
          <option v-for="d in DEFECTS" :key="d" :value="d">{{ d }}</option>
        </select>
        <input type="number" min="0" v-model.number="form.defectPoints" class="fld n" placeholder="不良点数" />
        <input type="number" min="1" v-model.number="form.sampleQty" class="fld n" placeholder="抽检板数" />
        <input v-model="form.inspector" class="fld" style="width:96px" placeholder="检验员" />
        <button class="act primary" :disabled="submitting" @click="create">
          {{ submitting ? '提交中…' : '登记质检' }}
        </button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">返修闭环</span>
        <span class="panel-desc">返修工完工 → 质检员复验；复验通过工序才置已完成，不通过轮次 +1</span>
      </div>

      <div class="stat-row">
        <div class="stat"><div class="k">质检记录</div><div class="v">{{ inspections.length }}</div></div>
        <div class="stat"><div class="k">判废</div><div class="v">{{ scrapped }}</div></div>
        <div class="stat"><div class="k">待返修</div><div class="v">{{ todoRepair.length }}</div></div>
        <div class="stat"><div class="k">待复验</div><div class="v">{{ todoRecheck.length }}</div></div>
      </div>

      <div class="lane-row">
        <div class="lane">
          <div class="lane-head"><span class="dot-tag orange"></span>待返修（返修工）</div>
          <div v-if="!todoRepair.length" class="lane-empty">暂无待返修任务</div>
          <div v-for="t in todoRepair" :key="t.id" class="rt-card">
            <div class="rt-main">
              <b>{{ orderNoOf(t.order?.id ?? t.orderId) }}</b>
              <span>{{ t.stepName }}</span>
              <em>第 {{ t.roundNo }} 轮</em>
            </div>
            <input v-model="repairBy" class="fld sm-in" placeholder="返修工" />
            <button class="act sm primary" :disabled="busyId === t.id" @click="completeRepair(t)">返修完工</button>
          </div>
        </div>
        <div class="lane">
          <div class="lane-head"><span class="dot-tag blue"></span>待复验（质检员）</div>
          <div v-if="!todoRecheck.length" class="lane-empty">暂无待复验任务</div>
          <div v-for="t in todoRecheck" :key="t.id" class="rt-card">
            <div class="rt-main">
              <b>{{ orderNoOf(t.order?.id ?? t.orderId) }}</b>
              <span>{{ t.stepName }}</span>
              <em>第 {{ t.roundNo }} 轮 · 返修人 {{ t.repairBy || '—' }}</em>
            </div>
            <input v-model="recheckInspector" class="fld sm-in" placeholder="复验质检员" />
            <button class="act sm primary" :disabled="busyId === t.id" @click="recheck(t, '合格')">复验通过</button>
            <button class="act sm danger" :disabled="busyId === t.id" @click="recheck(t, '不合格')">复验不通过</button>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">缺陷点阵</span>
        <span class="panel-desc">一格一点，铺满即判废；返修卡片随返修任务状态显示轮次与闭环进度</span>
      </div>

      <div class="insp-list">
        <div
          v-for="i in inspections"
          :key="i.id"
          class="insp-card"
          :class="{ scrap: i.verdict === '判废', repair: i.verdict === '返修' }"
        >
          <div class="insp-top">
            <span class="i-no">{{ i.orderNo }}</span>
            <span class="i-step">{{ i.stepName }}</span>
            <span class="i-type">{{ i.defectType }}</span>
            <span class="i-verdict">{{ i.verdict }}</span>
          </div>

          <div class="dot-matrix">
            <span v-for="p in points(i.defectPoints)" :key="p" class="dot"></span>
            <span v-if="i.defectPoints === 0" class="no-defect">无缺陷</span>
          </div>

          <div class="insp-foot">
            <span>{{ i.defectPoints }} 点 / 抽检 {{ i.sampleQty }} 板 · {{ i.defectRate }}‰ · {{ i.inspector }}</span>
            <span v-if="i.verdict === '返修'" class="rt-badge" :class="(taskByInspection.get(i.id)?.status) || 'none'">
              {{ taskByInspection.get(i.id)
                  ? `第${taskByInspection.get(i.id).roundNo}轮 · ${taskByInspection.get(i.id).status}`
                  : (i.repairDone ? '已闭环' : '返修任务补建中') }}
            </span>
            <span v-else class="stamp">{{ i.verdict === '判废' ? '判废' : '合格' }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.form-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }

.lane-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.lane { border: 1px solid var(--line); border-radius: 9px; padding: 10px; background: #fafbff; min-height: 90px; }
.lane-head { font-size: 13px; font-weight: 800; color: var(--primary-deep); margin-bottom: 8px; display: flex; align-items: center; gap: 6px; }
.dot-tag { width: 9px; height: 9px; border-radius: 50%; display: inline-block; }
.dot-tag.orange { background: #ef6c00; }
.dot-tag.blue { background: var(--primary-soft); }
.lane-empty { font-size: 12px; color: var(--muted); padding: 8px 2px; }
.rt-card {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  background: #fff; border: 1px solid var(--line); border-radius: 8px;
  padding: 8px 10px; margin-bottom: 8px;
}
.rt-main { display: flex; align-items: baseline; gap: 8px; margin-right: auto; font-size: 12px; min-width: 200px; }
.rt-main b { color: var(--primary-deep); font-size: 13px; }
.rt-main span { color: var(--muted); }
.rt-main em { font-style: normal; color: #ef6c00; font-weight: 700; }
.sm-in { width: 96px; padding: 4px 8px; font-size: 12px; }
.act.danger { border-color: #ef9a9a; color: #b71c1c; }
.act.danger:hover { background: #fff6f6; border-color: #c62828; }

.insp-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 10px; }

.insp-card {
  border: 1px solid var(--line); border-radius: 10px; padding: 10px 12px; background: #fff;
  display: flex; flex-direction: column; gap: 8px;
}
.insp-card.scrap { border-color: #ef9a9a; background: #fff6f6; }
.insp-card.repair { border-color: #ffcc80; background: #fffaf0; }

.insp-top { display: flex; align-items: center; gap: 6px; }
.i-no { font-weight: 800; color: var(--primary-deep); font-size: 12px; }
.i-step, .i-type { font-size: 11px; color: var(--muted); }
.i-verdict {
  margin-left: auto; font-size: 10px; font-weight: 800;
  padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep);
}
.insp-card.scrap .i-verdict { background: #ffcdd2; color: #b71c1c; }
.insp-card.repair .i-verdict { background: #ffe0b2; color: #e65100; }

.dot-matrix {
  display: flex; flex-wrap: wrap; gap: 3px;
  padding: 8px; border-radius: 7px; background: #f5f6fb; min-height: 40px; align-items: center;
}
.insp-card.scrap .dot-matrix { background: #ffebee; }
.dot { width: 11px; height: 11px; border-radius: 2px; background: var(--primary-soft); }
.insp-card.scrap .dot { background: #c62828; }
.insp-card.repair .dot { background: #ef6c00; }
.no-defect { font-size: 12px; color: #2e7d32; font-weight: 700; }

.insp-foot { display: flex; align-items: center; gap: 8px; font-size: 11px; color: var(--muted); flex-wrap: wrap; }
.insp-foot .stamp { margin-left: auto; font-weight: 800; color: var(--primary-deep); }
.rt-badge {
  margin-left: auto; font-size: 10px; font-weight: 800; padding: 2px 8px; border-radius: 9px;
  background: #ffe0b2; color: #e65100;
}
.rt-badge.待复验 { background: #d3d8f5; color: var(--primary-deep); }
.rt-badge.已复验通过 { background: #e3f6e8; color: #2e7d32; }
.rt-badge.none { background: var(--primary-wash); color: var(--muted); }
</style>
