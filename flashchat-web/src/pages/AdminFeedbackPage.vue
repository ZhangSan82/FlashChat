<template>
  <div class="fc-workspace af-page">
    <PageNoticeToast :notice="notice" />

    <div class="fc-shell fc-shell-md af-shell">
      <header class="fc-toolbar">
        <div class="fc-toolbar-row">
          <button class="fc-btn fc-btn-back" type="button" @click="goAdminHome">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
            <span>控制台</span>
          </button>
          <button class="fc-btn" type="button" @click="goRoomList">房间列表</button>
        </div>
        <span class="af-scope-chip">
          <i></i>仅管理员可访问
        </span>
      </header>

      <section class="af-hero-band">
        <div class="fc-hero">
          <div class="fc-hero-kicker">Admin · Feedback Desk</div>
          <h1>反馈处理台</h1>
          <p class="fc-hero-lede">
            承接用户提交的 Bug、建议与体验问题。按类型与状态快速筛选，对单条反馈做状态流转与处理备注，回应闭环。
          </p>
          <div class="fc-identity">
            <span>{{ auth.identity.value?.nickname || '管理员' }}</span>
            <span>{{ auth.identity.value?.accountId || '-' }}</span>
            <span>{{ auth.identity.value?.isAdmin ? '系统管理员' : '未授权' }}</span>
          </div>
        </div>

        <aside class="af-metrics">
          <article class="fc-stat">
            <span class="fc-stat-label">本页数量</span>
            <strong class="fc-stat-value">{{ feedbackPage.records.length }}</strong>
            <p class="fc-stat-hint">当前筛选下加载的条数</p>
          </article>
          <article class="fc-stat">
            <span class="fc-stat-label">总记录</span>
            <strong class="fc-stat-value">{{ feedbackPage.total }}</strong>
            <p class="fc-stat-hint">全量反馈数量</p>
          </article>
          <article class="fc-stat">
            <span class="fc-stat-label">当前选择</span>
            <strong class="fc-stat-value">{{ selectedFeedback?.id ? '#' + selectedFeedback.id : '—' }}</strong>
            <p class="fc-stat-hint">详情面板显示中的反馈</p>
          </article>
        </aside>
      </section>

      <section v-if="booting" class="fc-panel af-state">
        <div class="fc-state">
          <div class="fc-spinner"></div>
          <p>正在校验管理员身份并加载反馈数据...</p>
        </div>
      </section>

      <section v-else-if="accessDenied" class="fc-panel af-state">
        <div class="fc-state">
          <div class="fc-state-rule"></div>
          <div class="fc-section-label">Access Limited</div>
          <h2>当前账号没有管理员权限</h2>
          <p>你可以返回管理员控制台重新校验，或切换到具备管理员角色的账号后再进入。</p>
          <button class="fc-btn fc-btn-primary" type="button" @click="goAdminHome">返回控制台</button>
        </div>
      </section>

      <section v-else class="af-grid">
        <article class="fc-panel af-list-panel">
          <div class="fc-panel-head">
            <div class="fc-panel-head-copy">
              <span class="fc-section-label">Filters</span>
              <h2>筛选反馈列表</h2>
            </div>
            <div class="fc-panel-head-actions">
              <button class="fc-btn fc-btn-ghost" type="button" @click="loadFeedbacks(true)">刷新</button>
            </div>
          </div>

          <form class="af-filter-grid" @submit.prevent="loadFeedbacks(true)">
            <label class="fc-field fc-field-full">
              <span>关键字</span>
              <input v-model.trim="filters.keyword" type="text" placeholder="支持内容、账号 ID、昵称和联系方式搜索" />
            </label>

            <label class="fc-field">
              <span>状态</span>
              <select v-model="filters.status">
                <option value="">全部状态</option>
                <option value="NEW">待处理</option>
                <option value="PROCESSING">处理中</option>
                <option value="RESOLVED">已解决</option>
                <option value="CLOSED">已关闭</option>
              </select>
            </label>

            <label class="fc-field">
              <span>反馈类型</span>
              <select v-model="filters.feedbackType">
                <option value="">全部类型</option>
                <option value="SUGGESTION">功能建议</option>
                <option value="BUG">Bug 反馈</option>
                <option value="EXPERIENCE">体验问题</option>
                <option value="ACCOUNT">账号问题</option>
                <option value="ROOM">房间问题</option>
                <option value="OTHER">其他</option>
              </select>
            </label>

            <label class="fc-field">
              <span>提交身份</span>
              <select v-model="filters.accountType">
                <option value="">全部身份</option>
                <option value="GUEST">游客/未注册</option>
                <option value="REGISTERED">已注册用户</option>
              </select>
            </label>

            <div class="fc-field af-filter-actions">
              <button class="fc-btn fc-btn-primary" type="submit" :disabled="loading">查询反馈</button>
              <button class="fc-btn" type="button" @click="resetFilters">重置</button>
            </div>
          </form>

          <hr class="fc-divider" />

          <div v-if="loading && feedbackPage.records.length === 0" class="fc-state fc-state-compact">
            <div class="fc-spinner fc-spinner-sm"></div>
            <p>正在读取反馈列表...</p>
          </div>

          <div v-else-if="feedbackPage.records.length === 0" class="fc-state fc-state-compact">
            <div class="fc-state-rule"></div>
            <p>当前没有符合条件的反馈记录。</p>
          </div>

          <div v-else class="af-list">
            <button
              v-for="item in feedbackPage.records"
              :key="item.id"
              class="fc-row af-list-row"
              :class="{ 'is-active': selectedFeedback?.id === item.id }"
              type="button"
              @click="selectFeedback(item.id)"
            >
              <div class="af-list-id">#{{ item.id }}</div>
              <div class="fc-row-copy">
                <strong>{{ item.feedbackTypeDesc || item.feedbackType }}</strong>
                <p class="af-list-preview">{{ item.contentPreview || item.content || '无内容' }}</p>
                <div class="fc-row-meta">
                  <span>{{ item.accountId || item.accountTypeDesc || '匿名提交' }}</span>
                  <span>·</span>
                  <span>{{ item.sourcePage || '-' }}</span>
                  <span>·</span>
                  <span>{{ formatDateTime(item.createTime) }}</span>
                </div>
              </div>
              <div class="fc-row-chips">
                <span :class="['fc-chip', feedbackStatusChip(item.status)]">{{ item.statusDesc || item.status }}</span>
              </div>
            </button>
          </div>

          <div class="fc-pager">
            <button class="fc-btn" type="button" :disabled="loading || !canGoPrevPage" @click="changePage(-1)">上一页</button>
            <span class="fc-pager-info">第 {{ feedbackPage.page || 1 }} / {{ totalPages }} 页 · 共 {{ feedbackPage.total }} 条</span>
            <button class="fc-btn" type="button" :disabled="loading || !canGoNextPage" @click="changePage(1)">下一页</button>
          </div>
        </article>

        <article class="fc-panel af-detail-panel">
          <div class="fc-panel-head">
            <div class="fc-panel-head-copy">
              <span class="fc-section-label">Detail</span>
              <h2>反馈详情与处理</h2>
            </div>
          </div>

          <div v-if="detailLoading && !selectedFeedback" class="fc-state fc-state-compact">
            <div class="fc-spinner fc-spinner-sm"></div>
            <p>正在读取反馈详情...</p>
          </div>

          <div v-else-if="!selectedFeedback" class="fc-state">
            <div class="fc-state-rule"></div>
            <h3>请选择一条反馈</h3>
            <p>从左侧列表选择反馈后，可以查看完整内容、截图与上下文，并执行状态流转。</p>
          </div>

          <template v-else>
            <div class="af-detail-head">
              <div class="af-detail-copy">
                <span class="fc-section-label">Feedback #{{ selectedFeedback.id }}</span>
                <h3>{{ selectedFeedback.feedbackTypeDesc || selectedFeedback.feedbackType }}</h3>
                <p>{{ selectedFeedback.accountId || '匿名提交' }} · {{ selectedFeedback.accountTypeDesc || '-' }}</p>
              </div>
              <div class="af-detail-chips">
                <span :class="['fc-chip', feedbackStatusChip(selectedFeedback.status)]">{{ selectedFeedback.statusDesc || selectedFeedback.status }}</span>
                <span class="fc-chip fc-chip-plain">{{ selectedFeedback.sourcePage || '-' }}</span>
                <span class="fc-chip fc-chip-plain">{{ selectedFeedback.sourceScene || '-' }}</span>
              </div>
            </div>

            <div class="fc-info-tiles af-info-tiles">
              <div class="fc-info-tile">
                <span>提交时间</span>
                <strong>{{ formatDateTime(selectedFeedback.createTime) }}</strong>
              </div>
              <div class="fc-info-tile">
                <span>联系方式</span>
                <strong>{{ selectedFeedback.contact || '未填写' }}</strong>
              </div>
              <div class="fc-info-tile">
                <span>是否愿意联系</span>
                <strong>{{ selectedFeedback.willingContact ? '愿意' : '暂不需要' }}</strong>
              </div>
            </div>

            <section class="af-section">
              <div class="fc-subhead">
                <span class="fc-section-label">反馈正文</span>
              </div>
              <div class="af-content-body">
                <p>{{ selectedFeedback.content || '无内容' }}</p>
              </div>
            </section>

            <section v-if="selectedFeedback.screenshotUrl" class="af-section">
              <div class="fc-subhead">
                <span class="fc-section-label">问题截图</span>
              </div>
              <img class="af-shot" :src="selectedFeedback.screenshotUrl" alt="feedback screenshot" />
            </section>

            <section class="af-section af-process">
              <div class="fc-subhead">
                <span class="fc-section-label">状态流转</span>
                <span class="fc-hint">解决和关闭状态建议写清复现结论或修复说明。</span>
              </div>

              <form class="af-process-form" @submit.prevent="submitProcess">
                <label class="fc-field">
                  <span>目标状态</span>
                  <select v-model="processForm.status">
                    <option value="NEW">待处理</option>
                    <option value="PROCESSING">处理中</option>
                    <option value="RESOLVED">已解决</option>
                    <option value="CLOSED">已关闭</option>
                  </select>
                </label>

                <label class="fc-field fc-field-full">
                  <span>处理备注</span>
                  <textarea
                    v-model.trim="processForm.reply"
                    rows="6"
                    placeholder="记录复现结论、修复说明、关闭原因或补充说明。"
                  ></textarea>
                </label>

                <div v-if="selectedFeedback.reply" class="af-last-reply">
                  <span class="fc-section-label">当前备注</span>
                  <p>{{ selectedFeedback.reply }}</p>
                </div>

                <div class="af-process-actions">
                  <button class="fc-btn fc-btn-primary" type="submit" :disabled="processing">
                    {{ processing ? '提交中...' : '提交处理结果' }}
                  </button>
                </div>
              </form>
            </section>
          </template>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAdminFeedbackDetail, processAdminFeedback, searchAdminFeedbacks } from '@/api/feedback'
import PageNoticeToast from '@/components/PageNoticeToast.vue'
import { useAuth } from '@/composables/useAuth'
import { usePageNotice } from '@/composables/usePageNotice'

const router = useRouter()
const auth = useAuth()

const booting = ref(true)
const accessDenied = ref(false)
const loading = ref(false)
const detailLoading = ref(false)
const processing = ref(false)
const { notice, showNotice } = usePageNotice()

const feedbackPage = ref(normalizePage())
const selectedFeedback = ref(null)

const filters = reactive({
  keyword: '',
  status: '',
  feedbackType: '',
  accountType: '',
  page: 1,
  size: 10
})

const processForm = reactive({
  status: 'PROCESSING',
  reply: ''
})

const canGoPrevPage = computed(() => Number(feedbackPage.value.page || 1) > 1)
const canGoNextPage = computed(() => Number(feedbackPage.value.page || 1) * Number(feedbackPage.value.size || 10) < Number(feedbackPage.value.total || 0))
const totalPages = computed(() => {
  const total = Number(feedbackPage.value.total || 0)
  const size = Math.max(Number(feedbackPage.value.size || 10), 1)
  return Math.max(1, Math.ceil(total / size))
})

onMounted(async () => {
  await bootWorkspace()
})

async function bootWorkspace() {
  booting.value = true
  accessDenied.value = false

  if (!auth.hasToken()) {
    router.replace({ name: 'Welcome' })
    return
  }

  const valid = await auth.checkOnly()
  if (!valid) {
    router.replace({ name: 'Welcome' })
    return
  }

  if (!auth.identity.value?.isAdmin) {
    accessDenied.value = true
    booting.value = false
    return
  }

  await loadFeedbacks(true)
  booting.value = false
}

async function loadFeedbacks(reset = false) {
  if (reset) {
    filters.page = 1
  }

  loading.value = true
  try {
    const response = await searchAdminFeedbacks(buildParams())
    feedbackPage.value = normalizePage(response, filters.page, filters.size)

    if (!feedbackPage.value.records.length) {
      selectedFeedback.value = null
      return
    }

    const currentId = selectedFeedback.value?.id
    const nextId = feedbackPage.value.records.some(item => item.id === currentId)
      ? currentId
      : feedbackPage.value.records[0].id
    await selectFeedback(nextId)
  } catch (error) {
    showNotice(error?.message || '反馈列表加载失败', 'error')
  } finally {
    loading.value = false
  }
}

async function selectFeedback(feedbackId) {
  detailLoading.value = true
  try {
    const detail = await getAdminFeedbackDetail(feedbackId)
    selectedFeedback.value = detail
    processForm.status = detail.status || 'PROCESSING'
    processForm.reply = detail.reply || ''
  } catch (error) {
    showNotice(error?.message || '反馈详情加载失败', 'error')
  } finally {
    detailLoading.value = false
  }
}

async function submitProcess() {
  if (!selectedFeedback.value?.id) {
    showNotice('请先选择反馈记录', 'error')
    return
  }

  processing.value = true
  try {
    await processAdminFeedback(selectedFeedback.value.id, {
      status: processForm.status,
      reply: processForm.reply
    })
    showNotice('反馈处理状态已更新', 'success')
    await loadFeedbacks(false)
  } catch (error) {
    showNotice(error?.message || '反馈处理失败', 'error')
  } finally {
    processing.value = false
  }
}

function changePage(direction) {
  const next = Number(filters.page || 1) + direction
  if (next < 1) return
  filters.page = next
  loadFeedbacks(false)
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.feedbackType = ''
  filters.accountType = ''
  filters.page = 1
  loadFeedbacks(true)
}

function buildParams() {
  const params = {
    page: Number(filters.page || 1),
    size: Number(filters.size || 10)
  }
  if (filters.keyword) params.keyword = filters.keyword
  if (filters.status) params.status = filters.status
  if (filters.feedbackType) params.feedbackType = filters.feedbackType
  if (filters.accountType) params.accountType = filters.accountType
  return params
}

function normalizePage(data = null, page = 1, size = 10) {
  return {
    page: Number(data?.page || page),
    size: Number(data?.size || size),
    total: Number(data?.total || 0),
    records: Array.isArray(data?.records) ? data.records : []
  }
}

function goAdminHome() {
  router.push({ name: 'AdminControl' })
}

function goRoomList() {
  router.push({ name: 'Chat', query: { view: 'rooms' } })
}

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '-'
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  })
}

function feedbackStatusChip(status) {
  switch (status) {
    case 'NEW': return 'fc-chip-warn'
    case 'PROCESSING': return 'fc-chip-accent'
    case 'RESOLVED': return 'fc-chip-success'
    case 'CLOSED': return 'fc-chip-plain'
    default: return 'fc-chip-info'
  }
}

</script>

<style scoped>
.af-scope-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--fc-rule);
  background: rgba(255, 248, 237, 0.7);
  color: var(--fc-accent-strong);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.af-scope-chip i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--fc-accent);
  box-shadow: 0 0 0 4px rgba(182, 118, 57, 0.14);
  animation: af-pulse 1.8s var(--fc-ease-in-out) infinite;
}

@keyframes af-pulse {
  0%, 100% { box-shadow: 0 0 0 4px rgba(182, 118, 57, 0.14); }
  50%      { box-shadow: 0 0 0 7px rgba(182, 118, 57, 0.06); }
}

.af-hero-band {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 22px;
  align-items: stretch;
}

.af-metrics {
  display: grid;
  gap: 10px;
  align-content: start;
  margin-top: 22px;
}

.af-state {
  margin-top: 22px;
}

.af-grid {
  margin-top: 22px;
  display: grid;
  grid-template-columns: minmax(360px, 0.92fr) minmax(440px, 1.08fr);
  gap: 18px;
  align-items: start;
}

.af-filter-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.af-filter-actions {
  grid-column: 1 / -1;
  flex-direction: row;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.af-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.af-list-row {
  grid-template-columns: auto minmax(0, 1fr) auto;
  padding: 14px 16px 14px 20px;
}

.af-list-id {
  font-family: var(--fc-font-display);
  font-size: 18px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.01em;
  color: var(--fc-accent-strong);
  min-width: 42px;
}

.af-list-preview {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--fc-text-sec);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-word;
}

.af-detail-head {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  justify-content: space-between;
  align-items: flex-start;
}

.af-detail-copy h3 {
  margin: 6px 0 4px;
  font-family: var(--fc-font-display);
  font-size: 22px;
  line-height: 1.15;
  font-weight: 600;
  color: var(--fc-text);
}

.af-detail-copy p {
  margin: 0;
  font-size: 13px;
  color: var(--fc-text-muted);
}

.af-detail-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.af-info-tiles {
  margin-top: 18px;
}

.af-section {
  margin-top: 22px;
}

.af-content-body {
  padding: 14px 16px;
  border-left: 2px solid var(--fc-rule);
  background: rgba(255, 253, 249, 0.72);
  border-radius: 0 12px 12px 0;
}

.af-content-body p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text);
  white-space: pre-wrap;
  word-break: break-word;
}

.af-shot {
  width: 100%;
  max-height: 320px;
  object-fit: cover;
  border-radius: 16px;
  border: 1px solid var(--fc-border);
}

.af-process {
  margin-top: 24px;
  padding-top: 22px;
  border-top: 1px solid var(--fc-rule-soft);
}

.af-process-form {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 12px;
}

.af-last-reply {
  grid-column: 1 / -1;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid var(--fc-border);
  background: rgba(255, 253, 249, 0.86);
}

.af-last-reply p {
  margin: 6px 0 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--fc-text-sec);
}

.af-process-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

@media (max-width: 1060px) {
  .af-hero-band,
  .af-grid {
    grid-template-columns: 1fr;
  }
  .af-metrics {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    margin-top: 4px;
  }
}

@media (max-width: 720px) {
  .af-metrics {
    grid-template-columns: 1fr;
  }
  .af-filter-grid,
  .af-process-form {
    grid-template-columns: 1fr;
  }
  .af-list-row {
    grid-template-columns: auto minmax(0, 1fr);
  }
  .af-list-row .fc-row-chips {
    grid-column: 1 / -1;
    justify-content: flex-start;
  }
  .af-detail-head {
    flex-direction: column;
  }
}
</style>
