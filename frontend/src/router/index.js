import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import OrderView from '../views/OrderView.vue'
import KitView from '../views/KitView.vue'
import StepView from '../views/StepView.vue'
import LineChangeView from '../views/LineChangeView.vue'
import InspectionView from '../views/InspectionView.vue'

const routes = [
  { path: '/', redirect: '/order' },
  { path: '/order', name: 'order', component: OrderView },
  { path: '/kit', name: 'kit', component: KitView },
  { path: '/step', name: 'step', component: StepView },
  { path: '/line-change', name: 'line-change', component: LineChangeView },
  { path: '/inspection', name: 'inspection', component: InspectionView },
  { path: '/:pathMatch(.*)*', redirect: '/order' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
