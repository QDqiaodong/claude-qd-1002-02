import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 生产工单 ----------------
  listOrders: (p) => call(http.get('/work-orders', params(p))),
  getOrder: (id) => call(http.get(`/work-orders/${id}`)),
  kitCheck: (id) => call(http.get(`/work-orders/${id}/kit-check`)),
  shortageOrders: () => call(http.get('/work-orders/shortage')),
  createOrder: (d) => call(http.post('/work-orders', d)),
  updateOrder: (id, d) => call(http.put(`/work-orders/${id}`, d)),
  releaseOrder: (id) => call(http.post(`/work-orders/${id}/release`)),
  reportOrder: (id, qty) => call(http.post(`/work-orders/${id}/report`, null, params({ qty }))),
  transferOrder: (id, target) => call(http.post(`/work-orders/${id}/transfer`, null, params({ target }))),

  // ---------------- 物料 ----------------
  listComponents: (p) => call(http.get('/components', params(p))),
  createComponent: (d) => call(http.post('/components', d)),
  updateComponent: (id, d) => call(http.put(`/components/${id}`, d)),
  restockComponent: (id, qty) => call(http.post(`/components/${id}/restock`, null, params({ qty }))),
  issueComponent: (id, qty) => call(http.post(`/components/${id}/issue`, null, params({ qty }))),
  transferComponent: (id, target) => call(http.post(`/components/${id}/transfer`, null, params({ target }))),

  // ---------------- SMT 工序 ----------------
  listSteps: (p) => call(http.get('/smt-steps', params(p))),
  startStep: (id, operator) => call(http.post(`/smt-steps/${id}/start`, null, params({ operator }))),
  finishStep: (id) => call(http.post(`/smt-steps/${id}/finish`)),
  resetStep: (id) => call(http.post(`/smt-steps/${id}/reset`)),

  // ---------------- 换线首件放行 ----------------
  listLineChanges: (p) => call(http.get('/line-changes', params(p))),
  getLineChangeContext: (orderId) => call(http.get(`/line-changes/order/${orderId}/context`)),
  getLineChangeBlocking: (orderId) => call(http.get(`/line-changes/order/${orderId}/blocking`)),
  lineChangeSummary: () => call(http.get('/line-changes/summary')),
  createLineChange: (d) => call(http.post('/line-changes', d)),
  submitFirstPiece: (id, d) => call(http.post(`/line-changes/${id}/first-piece`, d)),
  confirmLineChange: (id, d) => call(http.post(`/line-changes/${id}/confirm`, d)),
  rejectLineChange: (id, d) => call(http.post(`/line-changes/${id}/reject`, d)),
  reviseLineChange: (id, d) => call(http.post(`/line-changes/${id}/revise`, d)),

  // ---------------- 质检与返修 ----------------
  listInspections: (p) => call(http.get('/inspections', params(p))),
  createInspection: (d) => call(http.post('/inspections', d)),
  // 旧接口保留：语义为返修工完工上报（转待复验），空体/带体都可，重复点击幂等
  repair: (id, d) => call(http.post(`/inspections/${id}/repair`, d || {})),
  openRepairs: () => call(http.get('/inspections/open-repairs')),
  blocking: (orderId) => call(http.get(`/inspections/blocking/${orderId}`)),

  // ---------------- 返修任务（闭环：返修工完工 → 质检员复验） ----------------
  listRepairTasks: (p) => call(http.get('/repair-tasks', params(p))),
  openRepairTasks: () => call(http.get('/repair-tasks/open')),
  getRepairTask: (id) => call(http.get(`/repair-tasks/${id}`)),
  repairTaskByInspection: (inspectionId) => call(http.get(`/repair-tasks/by-inspection/${inspectionId}`)),
  completeRepairTask: (id, d) => call(http.post(`/repair-tasks/${id}/complete`, d || {})),
  // 质检员复验：result 合格 → 闭环+工序已完成；不合格 → 返修轮次 +1
  recheckRepairTask: (id, d) => call(http.post(`/repair-tasks/${id}/recheck`, d))
}
