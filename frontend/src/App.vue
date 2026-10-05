<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import OrderView from './views/OrderView.vue'
import KitView from './views/KitView.vue'
import StepView from './views/StepView.vue'
import LineChangeView from './views/LineChangeView.vue'
import InspectionView from './views/InspectionView.vue'

// ------------------------------------------------------------------
// 导航形态：左侧纵向时间轴节点导航。
// 左栏不是菜单列表，而是一条竖线串起来的生产流程时间轴：
// 下单 → 备料 → 贴片 → 换线 → 质检，五个节点按真实工序顺序从上往下排，
// 节点圆点会随当前所在环节点亮；整条轴表达的是「这张工单走到哪一步了」，
// 而不是「系统有哪些功能」。没有顶栏，也没有图标栏。
// ------------------------------------------------------------------

const viewMap = {
  '/order': OrderView,
  '/kit': KitView,
  '/step': StepView,
  '/line-change': LineChangeView,
  '/inspection': InspectionView
}

const nodes = [
  { path: '/order', title: '生产工单', desc: '下单与排程', stage: '下单' },
  { path: '/kit', title: '齐套备料', desc: 'BOM 与物料', stage: '备料' },
  { path: '/step', title: 'SMT 工序', desc: '上料到目检', stage: '贴片' },
  { path: '/line-change', title: '换线首件', desc: '配置快照与放行', stage: '换线' },
  { path: '/inspection', title: '质检与返修', desc: '缺陷与闭环', stage: '质检' }
]

const currentPath = ref(router.currentRoute.value.path)
const counts = ref({ order: 0, kit: 0, step: 0, 'line-change': 0, inspection: 0 })

const currentView = computed(() => viewMap[currentPath.value] || OrderView)

async function loadCounts() {
  try {
    const [o, c, s, l, i] = await Promise.all([
      api.listOrders(), api.listComponents(), api.listSteps(), api.listLineChanges(), api.listInspections()
    ])
    counts.value = { order: o.length, kit: c.length, step: s.length, 'line-change': l.length, inspection: i.length }
  } catch (e) {
    // 计数失败不影响主流程
  }
}

function go(path) {
  router.push(path)
  currentPath.value = path
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadCounts()
})
</script>

<template>
  <div class="smt-app">
    <aside class="timeline">
      <div class="tl-brand">电子装配厂</div>
      <div class="tl-sub">SMT 工单与齐套装配</div>

      <div class="tl-rail">
        <div
          v-for="(n, i) in nodes"
          :key="n.path"
          class="tl-node"
          :class="{ on: currentPath === n.path }"
          @click="go(n.path)"
        >
          <div class="tl-title">{{ n.title }}</div>
          <div class="tl-desc">{{ n.desc }} · {{ counts[n.path.slice(1)] }}</div>
          <div class="tl-step">{{ i + 1 }} / {{ nodes.length }} · {{ n.stage }}</div>
        </div>
      </div>

      <div class="tl-foot">
        流程顺序：下单 → 备料 → 贴片 → 换线 → 质检<br />
        齐套不通过的工单无法投产；换线批次未放行时贴片工序暂停
      </div>
    </aside>

    <main class="main">
      <component :is="currentView" />
    </main>
  </div>
</template>
