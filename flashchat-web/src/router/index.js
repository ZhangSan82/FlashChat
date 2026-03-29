import { createRouter, createWebHistory } from 'vue-router'

const routes = [
    {
        path: '/',
        name: 'Chat',
        component: () => import('@/components/FlashChat.vue')
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
        path: '/invites',
        name: 'Invites',
        component: () => import('@/pages/InviteCenterPage.vue')
    },
    {
        path: '/room/:roomId',
        name: 'JoinRoom',
        component: () => import('@/pages/JoinLandingPage.vue'),
        props: true
    },
    {
        path: '/:pathMatch(.*)*',
        redirect: '/'
    }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router
