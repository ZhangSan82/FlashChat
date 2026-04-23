<template>
  <div class="af-page">
    <div class="af-shell">
      <header class="af-toolbar">
        <div class="af-toolbar-actions">
          <button class="af-back" type="button" @click="goAdminHome">
            <svg viewBox="0 0 24 24" aria-hidden="true">
              <path d="M15 6l-6 6 6 6" />
            </svg>
            <span>返回管理员控制台</span>
          </button>
          <button class="af-secondary-btn" type="button" @click="goRoomList">返回房间列表</button>
        </div>

        <transition name="af-notice-fade">
          <div v-if="notice" class="af-notice" :class="`is-${notice.type}`">{{ notice.text }}</div>
        </transition>
      </header>

      <section class="af-hero">
        <article class="af-main-card">
          <div class="fc-kicker">Admin Feedback</div>
          <h1>反馈处理台</h1>
          <p>这里承接用户提交的 Bug、建议与体验问题，管理员可以按类型和状态筛选，并对单条反馈做状态流转与处理备注。</p>
          <div class="af-meta">
            <span>{{ auth.identity.value?.accountId || '-' }}</span>
            <span>{{ auth.identity.value?.nickname || '管理员' }}</span>
            <span>仅管理员可访问</span>
          </div>
        </article>

        <aside class="af-stats">
          <article class="af-stat-card">
            <span>本页数量</span>
            <strong>{{ feedbackPage.records.length }}</strong>
          </article>
          <article class="af-stat-card">
            <span>总记录</span>
            <strong>{{ feedbackPage.total }}</strong>
          </article>
          <article class="af-stat-card">
            <span>当前选择</span>
            <strong>{{ selectedFeedback?.id || '-' }}</strong>
          </article>
        </aside>
      </section>

      <section v-if="booting" class="af-state-card">
        <div class="af-spinner"></div>
        <p>正在校验管理员身份并加载反馈数据...</p>
      </section>

      <section v-else-if="accessDenied" class="af-state-card">
        <div class="fc-kicker">Access Limited</div>
        <h2>当前账号没有管理员权限</h2>
        <p>你可以返回管理员控制台重新校验，或切换到具备管理员角色的账号后再进入。</p>
      </section>

      <section v-else class="af-grid">
        <article class="af-card">
          <div class="af-card-head">
            <div>
              <div class="fc-kicker">Filters</div>
              <h2>筛选列表</h2>
            </div>
            <button class="af-secondary-btn" type="button" @click="loadFeedbacks(true)">刷新列表</button>
          </div>

          <form class="af-filter-grid" @submit.prevent="loadFeedbacks(true)">
            <label class="af-field af-field-full">
              <span>关键字</span>
              <input v-model.trim="filters.keyword" type="text" placeholder="支持内容、账号 ID、昵称和联系方式搜索" />
            </label>

            <label class="af-field">
              <span>状态</span>
              <select v-model="filters.status">
                <option value="">全部状态</option>
                <option value="NEW">待处理</option>
                <option value="PROCESSING">处理中</option>
                <option value="RESOLVED">已解决</option>
                <option value="CLOSED">已关闭</option>
              </select>
            </label>

            <label class="af-field">
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

            <label class="af-field">
              <span>提交身份</span>
              <select v-model="filters.accountType">
                <option value="">全部身份</option>
                <option value="GUEST">游客/未注册</option>
                <option value="REGISTERED">已注册用户</option>
              </select>
            </label>

            <div class="af-filter-actions">
              <button class="af-primary-btn" type="submit" :disabled="loading">查询反馈</button>
              <button class="af-secondary-btn" type="button" @click="resetFilters">重置筛选</button>
            </div>
          </form>

          <div v-if="loading && feedbackPage.records.length === 0" class="af-list-state">
            <div class="af-spinner small"></div>
            <p>正在读取反馈列表...</p>
          </div>

          <div v-else-if="feedbackPage.records.length === 0" class="af-list-state">
            <p>当前没有符合条件的反馈记录。</p>
          </div>

          <div v-else class="af-list">
            <button
              v-for="item in feedbackPage.records"
              :key="item.id"
              class="af-list-item"
              :class="{ active: selectedFeedback?.id === item.id }"
              type="button"
              @click="selectFeedback(item.id)"
            >
              <div class="af-list-head">
                <strong>#{{ item.id }} · {{ item.feedbackTypeDesc || item.feedbackType }}</strong>
                <span>{{ item.statusDesc || item.status }}</span>
              </div>
              <p>{{ item.contentPreview || item.content || '无内容' }}</p>
              <div class="af-list-meta">
                <span>{{ item.accountId || item.accountTypeDesc || '匿名提交' }}</span>
                <span>{{ item.sourcePage || '-' }} / {{ item.sourceScene || '-' }}</span>
                <span>{{ formatDateTime(item.createTime) }}</span>
              </div>
            </button>
          </div>

          <div class="af-pager">
            <button class="af-secondary-btn" type="button" :disabled="loading || !canGoPrevPage" @click="changePage(-1)">上一页</button>
            <span>第 {{ feedbackPage.page || 1 }} 页 / 共 {{ totalPages }} 页</span>
            <button class="af-secondary-btn" type="button" :disabled="loading || !canGoNextPage" @click="changePage(1)">下一页</button>
          </div>
        </article>

        <article class="af-card">
          <div class="af-card-head">
            <div>
              <div class="fc-kicker">Detail</div>
              <h2>反馈详情与处理</h2>
            </div>
          </div>

          <div v-if="detailLoading && !selectedFeedback" class="af-list-state">
            <div class="af-spinner small"></div>
            <p>正在读取反馈详情...</p>
          </div>

          <div v-else-if="!selectedFeedback" class="af-list-state">
            <p>从左侧选择一条反馈后，可以查看完整内容、截图和上下文，并执行状态流转。</p>
          </div>

          <template v-else>
            <div class="af-detail-head">
              <div>
                <div class="fc-kicker">Feedback #{{ selectedFeedback.id }}</div>
                <h3>{{ selectedFeedback.feedbackTypeDesc || selectedFeedback.feedbackType }}</h3>
                <p>{{ selectedFeedback.accountId || '匿名提交' }} · {{ selectedFeedback.accountTypeDesc || '-' }}</p>
              </div>
              <div class="af-detail-chips">
                <span>{{ selectedFeedback.statusDesc || selectedFeedback.status }}</span>
                <span>{{ selectedFeedback.sourcePage || '-' }}</span>
                <span>{{ selectedFeedback.sourceScene || '-' }}</span>
              </div>
            </div>

            <div class="af-info-grid">
              <div class="af-info-item">
                <span>提交时间</span>
                <strong>{{ formatDateTime(selectedFeedback.createTime) }}</strong>
              </div>
              <div class="af-info-item">
                <span>联系方式</span>
                <strong>{{ selectedFeedback.contact || '未填写' }}</strong>
              </div>
              <div class="af-info-item">
                <span>是否愿意联系</span>
                <strong>{{ selectedFeedback.willingContact ? '愿意' : '暂不需要' }}</strong>
              </div>
            </div>

            <div class="af-content-card">
              <div class="fc-section-label">反馈正文</div>
              <p>{{ selectedFeedback.content || '无内容' }}</p>
            </div>

            <div v-if="selectedFeedback.screenshotUrl" class="af-content-card">
              <div class="fc-section-label">问题截图</div>
              <img class="af-shot" :src="selectedFeedback.screenshotUrl" alt="feedback screenshot" />
            </div>

            <div class="af-process-card">
              <div class="af-card-head af-process-head">
                <div>
                  <div class="fc-section-label">状态流转</div>
                  <h3>处理备注</h3>
                </div>
              </div>

              <form class="af-process-form" @submit.prevent="submitProcess">
                <label class="af-field">
                  <span>目标状态</span>
                  <select v-model="processForm.status">
                    <option value="NEW">待处理</option>
                    <option value="PROCESSING">处理中</option>
                    <option value="RESOLVED">已解决</option>
                    <option value="CLOSED">已关闭</option>
                  </select>
                </label>

                <label class="af-field af-field-full">
                  <span>处理备注</span>
                  <textarea
                    v-model.trim="processForm.reply"
                    rows="6"
                    placeholder="记录复现结论、修复说明、关闭原因或补充说明。解决和关闭状态建议填写清楚。"
                  ></textarea>
                </label>

                <div v-if="selectedFeedback.reply" class="af-last-reply">
                  <div class="fc-section-label">当前备注</div>
                  <p>{{ selectedFeedback.reply }}</p>
                </div>

                <div class="af-process-actions">
                  <button class="af-primary-btn" type="submit" :disabled="processing">
                    {{ processing ? '提交中...' : '提交处理结果' }}
                  </button>
                </div>
              </form>
            </div>
          </template>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAdminFeedbackDetail, processAdminFeedback, searchAdminFeedbacks } from '@/api/feedback'
import { useAuth } from '@/composables/useAuth'

const router = useRouter()
const auth = useAuth()

const booting = ref(true)
const accessDenied = ref(false)
const loading = ref(false)
const detailLoading = ref(false)
const processing = ref(false)
const notice = ref(null)

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

onUnmounted(() => {
  window.clearTimeout(showNotice.timer)
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

function showNotice(text, type = 'info') {
  notice.value = { text, type }
  window.clearTimeout(showNotice.timer)
  showNotice.timer = window.setTimeout(() => {
    notice.value = null
  }, 2400)
}
showNotice.timer = null
</script>

<style scoped>
.af-page {
  min-height: 100vh;
  padding: 20px 18px 28px;
  background: var(--fc-app-gradient);
}

.af-shell {
  max-width: 1280px;
  margin: 0 auto;
  padding: 20px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 28px;
  background: rgba(255, 253, 249, 0.92);
  box-shadow: 0 18px 42px rgba(33, 26, 20, 0.08);
}

.af-toolbar,
.af-toolbar-actions,
.af-hero,
.af-grid,
.af-card-head,
.af-filter-actions,
.af-list-head,
.af-list-meta,
.af-pager,
.af-detail-head,
.af-detail-chips,
.af-info-grid,
.af-process-actions {
  display: flex;
}

.af-toolbar,
.af-card-head,
.af-list-head,
.af-pager,
.af-detail-head {
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.af-toolbar-actions,
.af-filter-actions,
.af-detail-chips {
  flex-wrap: wrap;
  gap: 10px;
}

.af-back,
.af-primary-btn,
.af-secondary-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 10px 16px;
  border: 1px solid var(--fc-border);
  border-radius: 999px;
  font-family: var(--fc-font);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out), transform var(--fc-duration-normal) var(--fc-ease-in-out);
}

.af-back,
.af-secondary-btn {
  background: rgba(255, 255, 255, 0.84);
  color: var(--fc-text);
}

.af-back svg {
  width: 15px;
  height: 15px;
  stroke: currentColor;
  stroke-width: 1.9;
  fill: none;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.af-primary-btn {
  background: var(--fc-accent);
  border-color: transparent;
  color: #fffaf3;
  box-shadow: 0 10px 20px rgba(151, 90, 38, 0.16);
}

.af-back:hover,
.af-secondary-btn:hover {
  border-color: var(--fc-border-strong);
  background: var(--fc-bg-light);
  box-shadow: 0 0 0 3px rgba(182, 118, 57, 0.08);
}

.af-primary-btn:hover:not(:disabled) {
  background: var(--fc-accent-strong);
  transform: translateY(-1px);
}

.af-notice {
  padding: 9px 12px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.88);
  font-size: 12px;
  font-weight: 600;
  color: var(--fc-text);
}

.af-notice.is-success { color: var(--fc-success); }
.af-notice.is-error { color: var(--fc-danger); }
.af-notice-fade-enter-active,
.af-notice-fade-leave-active {
  transition: opacity var(--fc-duration-normal) var(--fc-ease-in-out), transform var(--fc-duration-normal) var(--fc-ease-in-out);
}
.af-notice-fade-enter-from,
.af-notice-fade-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.af-hero {
  margin-top: 18px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 250px;
  gap: 14px;
}

.af-main-card,
.af-stat-card,
.af-card,
.af-list-item,
.af-info-item,
.af-content-card,
.af-process-card,
.af-state-card {
  border: 1px solid rgba(77, 52, 31, 0.08);
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.84);
}

.af-main-card,
.af-card,
.af-state-card {
  padding: 22px;
}

.af-main-card h1,
.af-card-head h2,
.af-detail-head h3,
.af-process-head h3,
.af-state-card h2 {
  margin: 10px 0 6px;
  font-family: var(--fc-font-display);
  font-size: clamp(30px, 4vw, 40px);
  line-height: 1.06;
  font-weight: 600;
  color: var(--fc-text);
}

.af-card-head h2,
.af-detail-head h3,
.af-process-head h3,
.af-state-card h2 {
  font-size: 24px;
}

.af-main-card p,
.af-state-card p,
.af-detail-head p,
.af-list-item p,
.af-last-reply p,
.af-content-card p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--fc-text-sec);
}

.af-meta,
.af-detail-chips {
  margin-top: 16px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.af-meta span,
.af-detail-chips span {
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 255, 255, 0.9);
  font-size: 12px;
  color: var(--fc-text-sec);
}

.af-stats {
  display: grid;
  gap: 12px;
}

.af-stat-card {
  padding: 18px;
}

.af-stat-card span,
.af-info-item span,
.af-field span {
  display: block;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--fc-text-muted);
}

.af-stat-card strong,
.af-info-item strong {
  display: block;
  margin-top: 10px;
  font-family: var(--fc-font-display);
  font-size: 28px;
  line-height: 1;
  font-weight: 600;
  color: var(--fc-text);
}

.af-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: minmax(360px, 0.95fr) minmax(420px, 1.05fr);
  gap: 14px;
}

.af-filter-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.af-field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.af-field-full {
  grid-column: 1 / -1;
}

.af-field input,
.af-field select,
.af-field textarea {
  width: 100%;
  border: 1px solid rgba(77, 52, 31, 0.1);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.94);
  color: var(--fc-text);
  padding: 12px 14px;
}

.af-field textarea {
  min-height: 150px;
  resize: vertical;
  line-height: 1.7;
}

.af-list,
.af-list-state {
  margin-top: 16px;
}

.af-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.af-list-item {
  width: 100%;
  padding: 16px;
  text-align: left;
  cursor: pointer;
  transition: border-color var(--fc-duration-normal) var(--fc-ease-in-out), background var(--fc-duration-normal) var(--fc-ease-in-out), box-shadow var(--fc-duration-normal) var(--fc-ease-in-out);
}

.af-list-item:hover,
.af-list-item.active {
  border-color: var(--fc-selected-border);
  background: var(--fc-selected-bg);
  box-shadow: var(--fc-selected-shadow);
}

.af-list-head strong {
  font-size: 15px;
  color: var(--fc-text);
}

.af-list-head span,
.af-list-meta span {
  font-size: 12px;
  color: var(--fc-text-muted);
}

.af-list-item p {
  margin-top: 8px;
  word-break: break-word;
}

.af-list-meta {
  margin-top: 10px;
  flex-wrap: wrap;
  gap: 8px;
}

.af-list-state {
  padding: 30px 18px;
  text-align: center;
}

.af-list-state p {
  margin: 0;
}

.af-pager {
  margin-top: 16px;
}

.af-spinner {
  width: 30px;
  height: 30px;
  margin: 0 auto 12px;
  border: 2px solid rgba(77, 52, 31, 0.12);
  border-top-color: var(--fc-accent);
  border-radius: 50%;
  animation: af-spin 0.7s linear infinite;
}

.af-spinner.small {
  width: 24px;
  height: 24px;
}

@keyframes af-spin {
  to { transform: rotate(360deg); }
}

.af-info-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.af-info-item,
.af-content-card,
.af-process-card {
  margin-top: 16px;
  padding: 18px;
}

.af-shot {
  margin-top: 12px;
  width: 100%;
  max-height: 300px;
  object-fit: cover;
  border-radius: 18px;
}

.af-last-reply {
  margin-top: 14px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(77, 52, 31, 0.08);
  background: rgba(255, 253, 249, 0.86);
}

.af-process-actions {
  margin-top: 14px;
}

.af-back:disabled,
.af-primary-btn:disabled,
.af-secondary-btn:disabled {
  opacity: 0.58;
  cursor: not-allowed;
  box-shadow: none;
  transform: none;
}

.af-back:focus-visible,
.af-primary-btn:focus-visible,
.af-secondary-btn:focus-visible,
.af-field input:focus-visible,
.af-field select:focus-visible,
.af-field textarea:focus-visible,
.af-list-item:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--fc-focus-ring);
}

@media (max-width: 1060px) {
  .af-hero,
  .af-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .af-page {
    padding: 14px 12px 22px;
  }

  .af-shell {
    padding: 16px;
    border-radius: 22px;
  }

  .af-toolbar,
  .af-toolbar-actions,
  .af-card-head,
  .af-pager,
  .af-detail-head {
    flex-direction: column;
    align-items: stretch;
  }

  .af-filter-grid,
  .af-info-grid {
    grid-template-columns: 1fr;
  }

  .af-field-full {
    grid-column: auto;
  }
}
</style>
