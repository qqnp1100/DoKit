import { getBackendConfig } from '@dokit/web-utils';
import {
    getGlobalData
} from '../../store'
import {
    getCurrentInstance
} from 'vue';
import {
    $bus,
  } from '@dokit/web-utils'
export default class Socket {
    constructor(url) {
        this.instance = getCurrentInstance();
        this.state = getGlobalData();
        this.pendingQueries = new Map();
        this.socket = null;
        this.webSocketState = null;
        this.reconnectTimer = null;
        this.waitingServerTime = null;
        this.startHeartBeatTimee = null;
        this.heartBeat = {
            time: 30 * 1000,
            timeout: 3 * 1000,
            reconnect: 10 * 1000,
        }
        this.isReconnect = true;
        this.wsUrl = url;
        this.init();
        if (this.socket) {
            this.onopen(() => {
                this.state = getGlobalData();
                this.webSocketState = true
                this.send({
                    type: 'LOGIN',
                    channelSerial: this.state.channelSerial,
                    data: JSON.stringify({
                        manufacturer: window.location.host,
                        connectSerial: this.state.connectSerial || undefined
                    })
                })
                $bus.emit("webSocketState")
                this.startHeartBeat(this.heartBeat.time) // 心跳机制
            })
            this.onerror((e) => {
                console.log(e);
                this.webSocketState = false
                this.socket = null
                this.state.socketConnect = false
            })
            this.onclose((e) => {
                console.log(e)
                this.socket = null
                this.state.socketConnect = false
                this.webSocketState = false
                this.reconnectTimer && clearTimeout(this.reconnectTimer);
                this.waitingServerTime && clearTimeout(this.waitingServerTime);
                this.startHeartBeatTime && clearTimeout(this.startHeartBeatTime);
            })
            this.onmessage((e) => {
                let msg = JSON.parse(e.data);
                if (msg.type === 'DATA' && this.pendingQueries.has(msg.pid)) {
                    clearTimeout(this.pendingQueries.get(msg.pid));
                    this.pendingQueries.delete(msg.pid);
                }
                if (msg.type === "NOTIFY" && msg.contentType === "gateway_control") {
                    const command = JSON.parse(msg.data);
                    if (command.command === "set_host") {
                        const host = command.params && command.params.host === true;
                        this.state.isHost = host;
                        if (host) this.send({ type: "BROADCAST", contentType: "mc_host", channelSerial: this.state.channelSerial, data: JSON.stringify({ connectSerial: this.state.connectSerial }) });
                        Promise.resolve().then(() => this.send({ type: "NOTIFY", contentType: "gateway_control", channelSerial: this.state.channelSerial, data: JSON.stringify({ command: "host_response", message: "success", params: { host: this.state.isHost } }) }));
                    }
                }
                if (msg.type === "HEART_BEAT") {
                    this.webSocketState = true
                }
            })
        }
    }
    init() {
        if (!this.wsUrl || !this.wsUrl.trim()) return;
        try {
            if (!this.socket) {
                this.socket = new WebSocket(this.wsUrl)
            }
            if (this.socket && this.reconnectTimer) {
                clearTimeout(this.reconnectTimer)
                this.reconnectTimer = null
                this.socket = new WebSocket(this.wsUrl)
            }
        } catch (error) {
            console.error(error)
        }
    }
    onopen(callBack) {
        this.socket.addEventListener('open', callBack)
    }
    onclose(callBack) {
        this.socket.addEventListener('close', callBack)
    }
    onmessage(callBack) {
        this.socket.addEventListener('message', callBack)
    }
    onerror(callBack) {
        this.socket.addEventListener('error', callBack)
    }
    close() {
        this.socket && this.socket?.close()
        this.socket = null
        this.reconnectTimer && clearTimeout(this.reconnectTimer);
        this.waitingServerTime && clearTimeout(this.waitingServerTime);
        this.startHeartBeatTime && clearTimeout(this.startHeartBeatTime);
    }
    send(msg) {
        if (this.socket && msg.type === 'DATA' && msg.contentType === 'query' && msg.pid) {
            const target = this.socket;
            const timer = setTimeout(() => {
                this.pendingQueries.delete(msg.pid);
                target.dispatchEvent(new MessageEvent('message', { data: JSON.stringify({type:'DATA', pid:msg.pid, code:404, data:''}) }));
            }, getBackendConfig().queryTimeoutMs);
            this.pendingQueries.set(msg.pid, timer);
        }
        this.socket && this.socket.send(JSON.stringify(msg))
    }
    startHeartBeat(time) {
        this.startHeartBeatTime = setTimeout(() => {
            this.send({
                type: 'HEART_BEAT',
                data: JSON.stringify({
                    time: new Date(),
                    connectSerial: this.state.connectSerial
                })
            })
            this.waitingServer()
        }, time)
    }
    //延时等待服务端响应，通过webSocketState判断是否连线成功
    waitingServer() {
        this.webSocketState = false
        this.waitingServerTime = setTimeout(() => {
            if (this.webSocketState) {
                this.startHeartBeat(this.heartBeat.time)
                return
            }
            console.log('心跳无响应，已断线')
            try {
                this.close()
                this.instance.proxy.$toast("连接已经断开", 2000);
            } catch (e) {
                console.log('连接已关闭，无需关闭')
            }
            this.reconnectWebSocket()
        }, this.heartBeat.timeout)
    }
    reconnectWebSocket() {
        if (!this.isReconnect) {
            return;
        }
        this.reconnectTimer = setTimeout(() => {
            this.init()
        }, this.heartBeat.reconnect)
    }
}
