<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：快照对照 + 版本链。
// 顶部是阻塞横幅：谁在处理、为什么被阻塞、下一步能做什么，一眼到底；
// 中间左侧是当前生效配置与「建立换线批次」表单，右侧是当前批次的
// 快照对照卡（与上一版逐字段 diff，改动整行点亮）和按状态切换的
// 操作台（提交首件 / 确认放行 / 驳回 / 修正重提）；
// 底部是版本链 + 完整处理记录时间线。
// ------------------------------------------------------------------

const orders = ref([])
const selectedId = ref(null)
const ctx = ref(null)
const error = ref('')
const tip = ref('')
const busy = ref(false)

const createForm = ref({ equipmentNo: '', feederConfig: '', keyParams: '', reason: '', operator: '李敏' })
const firstForm = ref({ firstResult: '合格', firstNote: '', operator: '李敏' })
const confirmForm = ref({ inspector: '孙悦', reason: '' })
const reviseForm = ref({ equipmentNo: '', feederConfig: '', keyParams: '', note: '', operator: '李敏' })

const current = computed(() => (ctx.value && ctx.value.current) || null)
const config = computed(() => (ctx.value && ctx.value.config) || null)
const batches = computed(() => (ctx.value && ctx.value.batches) || [])
const events = computed(() => (ctx.value && ctx.value.events) || [])
const diff = computed(() => (ctx.value && ctx.value.diff) || [])

const statusCount = computed(() => {
  const m = { 待首件: 0, 待确认: 0, 已放行: 0, 已驳回: 0, 已失效: 0 }
  for (const b of batches.value) m[b.status] = (m[b.status] || 0) + 1
  return m
})

async function load() {
  error.value = ''
  try {
    orders.value = await api.listOrders()
    const q = router.currentRoute.value.query.order
    if (q && !selectedId.value) selectedId.value = Number(q)
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
    await loadCtx()
  } catch (e) { error.value = e.message }
}

async function loadCtx() {
  if (!selectedId.value) { ctx.value = null; return }
  try {
    ctx.value = await api.getLineChangeContext(selectedId.value)
    // 建批 / 修正表单默认带出当前生效配置，改哪项填哪项
    const c = ctx.value.config
    if (c) {
      for (const f of [createForm, reviseForm]) {
        f.value.equipmentNo = c.equipmentNo
        f.value.feederConfig = c.feederConfig
        f.value.keyParams = c.keyParams
      }
    }
  } catch (e) { error.value = e.message }
}

// 统一动作入口：busy 防重复点击，后端另有状态机 + 锁兜底
async function run(fn, okMsg) {
  if (busy.value) return
  busy.value = true
  error.value = ''; tip.value = ''
  try {
    await fn()
    tip.value = okMsg
    confirmForm.value.reason = ''
    await loadCtx()
  } catch (e) { error.value = e.message }
  finally { busy.value = false }
}

const createBatch = () => run(
  () => api.createLineChange({ orderId: selectedId.value, ...createForm.value }),
  '换线批次已建立，等待提交首件'
)
const submitFirst = () => run(
  () => api.submitFirstPiece(current.value.id, firstForm.value),
  '首件检测结果已提交，等待质检员确认'
)
const confirm = () => run(
  () => api.confirmLineChange(current.value.id, { inspector: confirmForm.value.inspector }),
  '首件已确认放行，恢复批量贴片'
)
const reject = () => run(
  () => api.rejectLineChange(current.value.id, { inspector: confirmForm.value.inspector, reason: confirmForm.value.reason }),
  '已驳回，等待操作员修正重提'
)
const revise = () => run(
  () => api.reviseLineChange(current.value.id, reviseForm.value),
  '已生成新版本批次，等待提交首件'
)

watch(selectedId, () => loadCtx())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">换线首件放行 · 快照对照</span>
        <span class="panel-desc">换设备 / 飞达 / 关键参数必须先建批次，首件确认通过才能恢复贴片</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">待首件</div><div class="v">{{ statusCount['待首件'] }}</div></div>
        <div class="stat"><div class="k">待确认</div><div class="v">{{ statusCount['待确认'] }}</div></div>
        <div class="stat"><div class="k">已放行</div><div class="v">{{ statusCount['已放行'] }}</div></div>
        <div class="stat"><div class="k">已驳回</div><div class="v">{{ statusCount['已驳回'] }}</div></div>
        <div class="stat"><div class="k">已失效</div><div class="v">{{ statusCount['已失效'] }}</div></div>
      </div>

      <div class="pick-row">
        <span class="pick-label">工单</span>
        <select v-model="selectedId" class="fld">
          <option v-for="o in orders" :key="o.id" :value="o.id">
            {{ o.orderNo }} · {{ o.productModel }} · {{ o.status }}
          </option>
        </select>
      </div>

      <!-- 阻塞横幅：为什么被阻塞、谁在处理、下一步动作 -->
      <div v-if="ctx && ctx.blocked && current" class="block-banner">
        <div class="bb-title">
          贴片已暂停 —— 换线批次 V{{ current.versionNo }}（{{ current.status }}）未放行
        </div>
        <div class="bb-line">处理人：{{ ctx.handler }} ｜ 下一步：{{ ctx.nextAction }}</div>
        <div class="bb-line" v-if="current.status === '已驳回' && current.rejectReason">
          驳回原因：{{ current.rejectReason }}
        </div>
      </div>
      <div v-else-if="ctx && current" class="free-banner">
        当前无换线阻塞 —— 最新批次 V{{ current.versionNo }}（{{ current.status }}），贴片工序可正常推进
      </div>
      <div v-else-if="ctx" class="free-banner">本工单还没有换线批次，贴片工序不受换线约束</div>
    </div>

    <div class="two-col">
      <!-- 左：当前生效配置 + 建立换线批次 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">当前生效配置</span>
          <span class="panel-desc" v-if="config">{{ config.updatedBy }} 更新于 {{ config.updatedAt }}</span>
        </div>
        <div v-if="config" class="cfg-list">
          <div class="cfg-row"><span class="cfg-k">生产设备</span><span class="cfg-v">{{ config.equipmentNo }}</span></div>
          <div class="cfg-row"><span class="cfg-k">飞达配置</span><span class="cfg-v">{{ config.feederConfig }}</span></div>
          <div class="cfg-row"><span class="cfg-k">关键参数</span><span class="cfg-v">{{ config.keyParams }}</span></div>
        </div>
        <div v-else class="note">本工单尚未登记产线配置（投产时会自动登记初始配置）。</div>

        <template v-if="ctx && ctx.orderStatus === '贴片中'">
          <div class="sub-title">建立换线批次（改哪项填哪项，配置有变化才会建批）</div>
          <div class="form-grid">
            <label>生产设备</label>
            <input v-model="createForm.equipmentNo" class="fld" placeholder="如 SMT-02" />
            <label>飞达配置</label>
            <input v-model="createForm.feederConfig" class="fld" placeholder="如 F01:RC-0603-1K;F04:IC-QFP-MCU" />
            <label>关键参数</label>
            <input v-model="createForm.keyParams" class="fld" placeholder="如 峰值温度245℃；链速80cm/min" />
            <label>换线原因</label>
            <input v-model="createForm.reason" class="fld" placeholder="为什么换线" />
            <label>操作员</label>
            <input v-model="createForm.operator" class="fld" />
          </div>
          <div class="op-row">
            <button class="act primary" :disabled="busy" @click="createBatch">建立换线批次</button>
            <span class="note">建批后旧有效批次自动失效，贴片工序暂停，直到新版本首件放行</span>
          </div>
        </template>
        <div v-else-if="ctx" class="note">只有「贴片中」的工单能建立换线批次，当前工单状态：{{ ctx.orderStatus }}</div>
      </div>

      <!-- 右：当前批次快照对照 + 状态操作台 -->
      <div class="panel" v-if="current">
        <div class="panel-head">
          <span class="panel-title">当前批次 V{{ current.versionNo }}</span>
          <span class="stamp" :class="'st-' + current.status">{{ current.status }}</span>
          <span v-if="ctx.stale" class="stale-tag">快照已过期</span>
        </div>

        <!-- 快照对照：与上一版逐字段 diff -->
        <div class="diff-list">
          <div v-for="d in diff" :key="d.label" class="diff-row" :class="{ changed: d.changed }">
            <span class="d-label">{{ d.label }}</span>
            <span class="d-from">{{ d.from || '（无上版）' }}</span>
            <span class="d-arrow">→</span>
            <span class="d-to">{{ d.to }}</span>
          </div>
        </div>
        <div class="note">换线原因：{{ current.changeReason }} ｜ 建批：{{ current.createdBy }} · {{ current.createdAt }}</div>

        <!-- 首件数据 -->
        <div v-if="current.firstResult" class="first-box">
          <span class="fb-tag" :class="current.firstResult === '合格' ? 'good' : 'bad'">首件{{ current.firstResult }}</span>
          <span>{{ current.firstNote || '无检测说明' }}</span>
          <span class="fb-meta">提交人 {{ current.submittedBy }} · {{ current.submittedAt }}</span>
        </div>
        <div v-if="current.rejectReason" class="reject-box">驳回原因：{{ current.rejectReason }}（{{ current.confirmedBy }} · {{ current.confirmedAt }}）</div>
        <div v-if="current.status === '已放行'" class="release-box">已放行：{{ current.confirmedBy }} 确认于 {{ current.confirmedAt }}，可恢复批量贴片</div>

        <!-- 操作台：按状态切换 -->
        <div v-if="current.status === '待首件'" class="op-panel">
          <div class="sub-title">提交首件检测（提交后只能等质检员确认）</div>
          <div class="op-row">
            <select v-model="firstForm.firstResult" class="fld">
              <option>合格</option><option>不合格</option>
            </select>
            <input v-model="firstForm.firstNote" class="fld grow" placeholder="首件检测数据，如 首件 AOI 检测合格" />
            <input v-model="firstForm.operator" class="fld" style="width:90px" placeholder="操作员" />
            <button class="act primary" :disabled="busy" @click="submitFirst">提交首件</button>
          </div>
        </div>

        <div v-else-if="current.status === '待确认'" class="op-panel">
          <div class="sub-title">质检员确认（提交人 {{ current.submittedBy }} 不能自审；配置被再修改会确认失败并失效）</div>
          <div class="op-row">
            <input v-model="confirmForm.inspector" class="fld" style="width:90px" placeholder="质检员" />
            <button class="act primary" :disabled="busy" @click="confirm">确认放行</button>
            <input v-model="confirmForm.reason" class="fld grow" placeholder="驳回原因（驳回必填）" />
            <button class="act danger" :disabled="busy" @click="reject">驳回</button>
          </div>
        </div>

        <div v-else-if="current.status === '已驳回'" class="op-panel">
          <div class="sub-title">修正重提：按修正后的配置生成 V{{ current.versionNo + 1 }} 重新送检</div>
          <div class="form-grid">
            <label>生产设备</label><input v-model="reviseForm.equipmentNo" class="fld" />
            <label>飞达配置</label><input v-model="reviseForm.feederConfig" class="fld" />
            <label>关键参数</label><input v-model="reviseForm.keyParams" class="fld" />
            <label>修正说明</label><input v-model="reviseForm.note" class="fld" placeholder="针对驳回原因做了什么修正" />
            <label>操作员</label><input v-model="reviseForm.operator" class="fld" />
          </div>
          <div class="op-row">
            <button class="act primary" :disabled="busy" @click="revise">修正重提 · 生成新版本</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 版本链 + 处理记录 -->
    <div class="panel" v-if="batches.length">
      <div class="panel-head">
        <span class="panel-title">版本链与处理记录</span>
        <span class="panel-desc">同一工单最多一个有效版本；完整处理记录可追溯</span>
      </div>

      <div class="chain">
        <div
          v-for="b in [...batches].reverse()"
          :key="b.id"
          class="chain-node"
          :class="['st-' + b.status, { cur: current && b.id === current.id }]"
        >
          <div class="cn-ver">V{{ b.versionNo }}</div>
          <div class="cn-st">{{ b.status }}</div>
          <div class="cn-meta">{{ b.equipmentNo }}</div>
          <div class="cn-meta">{{ b.createdBy }}</div>
        </div>
      </div>

      <div class="event-list">
        <div v-for="e in events" :key="e.id" class="event-row">
          <span class="e-time">{{ e.createdAt }}</span>
          <span class="e-ver">V{{ e.batchVersionNo }}</span>
          <span class="e-act" :class="'a-' + e.action">{{ e.action }}</span>
          <span class="e-actor">{{ e.actor }}</span>
          <span class="e-detail">{{ e.detail }}</span>
        </div>
        <div v-if="!events.length" class="note">暂无处理记录</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.pick-row { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.pick-label { font-size: 13px; color: var(--muted); }
.note { font-size: 11px; color: var(--muted); margin-top: 6px; }
.sub-title { font-size: 13px; font-weight: 800; color: var(--primary-deep); margin: 14px 0 8px; }

/* 阻塞 / 放行横幅 */
.block-banner {
  border: 1px solid #f5c6c2; background: #fdecea; color: #a32b20;
  border-radius: 9px; padding: 10px 14px;
}
.bb-title { font-weight: 800; font-size: 14px; }
.bb-line { font-size: 12px; margin-top: 4px; }
.free-banner {
  border: 1px solid #c8e6c9; background: #eef7ee; color: #2e7d32;
  border-radius: 9px; padding: 10px 14px; font-size: 13px; font-weight: 700;
}

.two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }
@media (max-width: 1100px) { .two-col { grid-template-columns: 1fr; } }

/* 当前生效配置 */
.cfg-list { display: flex; flex-direction: column; gap: 6px; }
.cfg-row { display: grid; grid-template-columns: 72px 1fr; gap: 8px; font-size: 12px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; }
.cfg-k { color: var(--muted); font-weight: 700; }
.cfg-v { color: var(--ink); word-break: break-all; }

.form-grid { display: grid; grid-template-columns: 72px 1fr; gap: 8px; align-items: center; }
.form-grid label { font-size: 12px; color: var(--muted); font-weight: 700; }
.op-row { display: flex; gap: 8px; align-items: center; margin-top: 10px; flex-wrap: wrap; }
.grow { flex: 1; min-width: 160px; }

/* 状态章 */
.stamp { font-size: 11px; font-weight: 800; padding: 2px 10px; border-radius: 10px; }
.st-待首件 { background: #fff3e0; color: #e65100; }
.st-待确认 { background: #e3f2fd; color: #1565c0; }
.st-已放行 { background: #e8f5e9; color: #2e7d32; }
.st-已驳回 { background: #fdecea; color: #c62828; }
.st-已失效 { background: #eceff1; color: #78909c; }
.stale-tag { font-size: 11px; font-weight: 800; padding: 2px 10px; border-radius: 10px; background: #fdecea; color: #c62828; }

/* 快照对照 diff */
.diff-list { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; }
.diff-row {
  display: grid; grid-template-columns: 72px 1fr 20px 1fr; gap: 8px; align-items: center;
  font-size: 12px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 8px;
}
.diff-row.changed { border-color: var(--primary-soft); background: var(--primary-wash); }
.d-label { color: var(--muted); font-weight: 700; }
.d-from { color: var(--muted); text-decoration: line-through; word-break: break-all; }
.diff-row.changed .d-from { color: #c62828; }
.d-arrow { color: var(--muted); text-align: center; }
.d-to { font-weight: 700; color: var(--primary-deep); word-break: break-all; }

/* 首件 / 驳回 / 放行信息块 */
.first-box {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; font-size: 12px; margin-top: 8px;
}
.fb-tag { font-weight: 800; padding: 1px 8px; border-radius: 9px; font-size: 11px; }
.fb-tag.good { background: #e8f5e9; color: #2e7d32; }
.fb-tag.bad { background: #fdecea; color: #c62828; }
.fb-meta { margin-left: auto; color: var(--muted); font-size: 11px; }
.reject-box { border: 1px solid #f5c6c2; background: #fdecea; color: #a32b20; border-radius: 8px; padding: 8px 10px; font-size: 12px; margin-top: 8px; }
.release-box { border: 1px solid #c8e6c9; background: #eef7ee; color: #2e7d32; border-radius: 8px; padding: 8px 10px; font-size: 12px; margin-top: 8px; font-weight: 700; }

.op-panel { margin-top: 10px; border-top: 1px dashed var(--line); padding-top: 4px; }
.act.danger { border-color: #f5c6c2; color: #c62828; }
.act.danger:hover { background: #fdecea; }

/* 版本链 */
.chain { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.chain-node {
  min-width: 96px; border: 1px solid var(--line); border-radius: 9px;
  padding: 8px 12px; text-align: center; background: #fff;
}
.chain-node.cur { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.cn-ver { font-weight: 800; color: var(--primary-deep); font-size: 14px; }
.cn-st { font-size: 11px; font-weight: 800; margin: 3px 0; padding: 1px 8px; border-radius: 9px; display: inline-block; }
.chain-node.st-待首件 .cn-st { background: #fff3e0; color: #e65100; }
.chain-node.st-待确认 .cn-st { background: #e3f2fd; color: #1565c0; }
.chain-node.st-已放行 .cn-st { background: #e8f5e9; color: #2e7d32; }
.chain-node.st-已驳回 .cn-st { background: #fdecea; color: #c62828; }
.chain-node.st-已失效 .cn-st { background: #eceff1; color: #78909c; }
.chain-node.st-已失效 { opacity: 0.66; }
.cn-meta { font-size: 10px; color: var(--muted); margin-top: 2px; }

/* 处理记录时间线 */
.event-list { display: flex; flex-direction: column; gap: 5px; }
.event-row {
  display: grid; grid-template-columns: 150px 40px 76px 64px 1fr; gap: 8px; align-items: center;
  font-size: 12px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px;
}
.e-time { color: var(--muted); font-size: 11px; }
.e-ver { font-weight: 800; color: var(--primary-deep); }
.e-act { font-weight: 800; font-size: 11px; padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); text-align: center; }
.e-act.a-驳回, .e-act.a-批次失效 { background: #fdecea; color: #c62828; }
.e-act.a-确认放行 { background: #e8f5e9; color: #2e7d32; }
.e-actor { color: var(--ink); }
.e-detail { color: var(--muted); word-break: break-all; }
</style>
