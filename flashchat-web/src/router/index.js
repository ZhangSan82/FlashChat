import { createRouter, createWebHistory } from 'vue-router'
import { loadIdentity, loadToken } from '@/utils/storage'

const routes = [
    {
        path: '/',
        name: 'Chat',
        component: () => import('@/components/FlashChat.vue')
    },
    {
        path: '/welcome',
        name: 'Welcome',
        component: () => import('@/pages/WelcomePage.vue')
    },
    {
        path: '/login',
        name: 'Login',
        component: () => import('@/pages/LoginPage.vue')
    },
    {
        path: '/register',
        name: 'Register',
        component: () => import('@/pages/RegisterPage.vue')
    },
    {
        path: '/room/public',
        name: 'PublicRooms',
        component: () => import('@/pages/PublicLobbyPage.vue')
    },
    {
        path: '/credits',
        name: 'Credits',
        component: () => import('@/pages/CreditsCenterPage.vue')
    },
    {
        path: '/feedback',
        name: 'FeedbackCenter',
        component: () => import('@/pages/FeedbackCenterPage.vue')
    },
    {
        path: '/invites',
        name: 'Invites',
        component: () => import('@/pages/InviteCenterPage.vue')
    },
    {
        path: '/admin',
        name: 'AdminControl',
        component: () => import('@/pages/AdminControlPage.vue')
    },
    {
        path: '/admin/feedbacks',
        name: 'AdminFeedbacks',
        component: () => import('@/pages/AdminFeedbackPage.vue')
    },
    {
        path: '/room/:roomId',
        name: 'JoinRoom',
        component: () => import('@/pages/JoinLandingPage.vue'),
        props: true
    },
    {
        path: '/entry-choice',
        name: 'EntryChoice',
        component: () => import('@/pages/EntryChoicePage.vue'),
        props: route => ({ roomId: route.query.roomId })
    },
    {
        path: '/:pathMatch(.*)*',
        redirect: '/'
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes,
    scrollBehavior(to, from, savedPosition) {
        if (savedPosition) {
            return savedPosition
        }
        if (to.hash) {
            return { el: to.hash, behavior: 'smooth' }
        }
        return { left: 0, top: 0 }
    }
})

router.beforeEach((to) => {
    const token = loadToken()
    const identity = loadIdentity()

    if (to.name === 'Chat' && !token) {
        return { name: 'Welcome' }
    }

    if (to.name === 'AdminControl' || to.name === 'AdminFeedbacks') {
        if (!token) {
            return { name: 'Welcome' }
        }
        if (identity && !identity.isAdmin) {
            return { path: '/' }
        }
    }

    if (to.name === 'Welcome' && token) {
        return { path: '/' }
    }

    if (to.name === 'JoinRoom' && !token) {
        return { name: 'EntryChoice', query: { roomId: to.params.roomId } }
    }

    if (to.name === 'EntryChoice') {
        if (token && to.query.roomId) {
            return { name: 'JoinRoom', params: { roomId: to.query.roomId } }
        }
        if (!to.query.roomId) {
            return { path: '/' }
        }
    }

    return true
})

export default router
