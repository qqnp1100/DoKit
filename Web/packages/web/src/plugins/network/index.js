import Network from './main.vue'
import {getGlobalData, RouterPlugin} from '@dokit/web-core'
import { request } from "./../../assets/util";


export default new RouterPlugin({
  name: 'Network',
  nameZh: '网络请求',
  component: Network,
  icon: 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2264%22 height=%2264%22%3E%3Crect width=%2264%22 height=%2264%22 rx=%2214%22 fill=%22%232467eb%22/%3E%3Ctext x=%2232%22 y=%2243%22 text-anchor=%22middle%22 fill=%22white%22 font-size=%2230%22%3EDK%3C/text%3E%3C/svg%3E',
  onLoad(){
    let state = getGlobalData()
    state.requestList = []
    state.recorderRequestList = []
    request.on('REQUEST.SEND', info=> {
      console.log('REQUEST.SEND',info)
      state.requestList.push(info)
      state.startRecorder&&state.recorderRequestList.push(info)
    })

    request.on('REQUEST.DONE', info => {
      console.log('REQUEST.DONE',info)
      let index = state.requestList.findIndex(e => e.id === info.id)
      let recorderIndex = state.recorderRequestList.findIndex(e => e.id === info.id)
      state.requestList[index].responseInfo = info.responseInfo
      state.startRecorder&&recorderIndex!==-1&&(state.recorderRequestList[recorderIndex].responseInfo = info.responseInfo,console.log(state.recorderRequestList))
    })

    request.on('REQUEST.ERROR', info => {
      console.log('REQUEST.ERROR',info)
      let index = state.requestList.findIndex(e => e.id === info.id)
      let recorderIndex = state.recorderRequestList.findIndex(e => e.id === info.id)
      state.requestList[index].responseInfo = {...state.requestList[index].responseInfo, ...info.responseInfo}
      state.startRecorder&&(state.recorderRequestList[recorderIndex].responseInfo = {...state.recorderRequestList[recorderIndex].responseInfo, ...info.responseInfo},console.log(state.recorderRequestList))
    })
  },
  onUnload(){

  }
})