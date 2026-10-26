import ApiMock from './main.vue'
import {getGlobalData, RouterPlugin} from '@dokit/web-core'
import { getPartUrlByParam } from "@dokit/web-utils";
import { request } from './../../assets/util'

import { getBackendConfig, isBackendUrl as isBackend } from '@dokit/web-utils';

const getCheckedInterfaceList = function (interfaceList) {
  return interfaceList.filter(i => i.checked)
}

// 将本地接口数据 和 线上数据合并  主要是确认哪些接口/场景已经在本地开启
// TODO: 性能有待优化
const getMergeData = function (list) {
  let localList = JSON.parse(localStorage.getItem('dokit-interface-list:' + getBackendConfig().mockBaseUrl + ':' + getGlobalData().productId) || '[]')
  list.forEach(i => {
    localList.forEach(l => {
      if (i._id === l._id) {
        i.checked = l.checked
        i.sceneList.forEach(scene => {
          l.sceneList.forEach(s => {
            if (scene._id === s._id) {
              scene.checked = s.checked
            }
          })
        });
      }
    })
  })

  return list
}

export default new RouterPlugin({
  name: 'ApiMock',
  nameZh: '数据mock',
  component: ApiMock,
  icon: 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2264%22 height=%2264%22%3E%3Crect width=%2264%22 height=%2264%22 rx=%2214%22 fill=%22%232467eb%22/%3E%3Ctext x=%2232%22 y=%2243%22 text-anchor=%22middle%22 fill=%22white%22 font-size=%2230%22%3EDK%3C/text%3E%3C/svg%3E',
  onProductReady(){
    let state = getGlobalData();
    if (!getBackendConfig().mockBaseUrl || !state.productId) return;
    // 获取接口数据
    fetch(
      `${getBackendConfig().mockBaseUrl}/api/app/interface?projectId=${encodeURIComponent(state.productId)}&isfull=1`,
      {
        mode: "cors",
        headers: { "X-DoKit-Token": getBackendConfig().sdkToken },
      }
    )
      .then((response) => {
        return response.json();
      })
      .then((info) => {
        let list = (info.data && info.data.datalist) || [];
        list.forEach((element) => {
          element.checked = false;
          element.sceneList.forEach((scene, index) => {
            scene.checked = false;
            if (index === 0) {
              scene.checked = true;
            }
          });
        });
        
        state.interfaceList = getMergeData(list)
      });



      request.hookFetch({
        onBeforeFetch: (fetchArgs) => {
          let checkedInterfaceList = getCheckedInterfaceList(state.interfaceList)
          let url = fetchArgs[0].url || fetchArgs[0];
          if (isBackend(url)) return fetchArgs;
          let path = `/`+getPartUrlByParam(url, 'path')
          let sceneId = ''
          checkedInterfaceList.forEach(i => {
            if(i.path === path) {
              i.sceneList.forEach(scene => {
                if(scene.checked) {
                  sceneId = scene._id
                }
              })
            }
          })
          if (sceneId) {
            fetchArgs[1] = { method: 'GET', mode: 'cors', credentials: 'omit', headers: { 'X-DoKit-Token': getBackendConfig().sdkToken },
              signal: fetchArgs[1] && fetchArgs[1].signal };
          }
          sceneId && (fetchArgs[0] = `${getBackendConfig().mockBaseUrl}/api/app/scene/${sceneId}`)
          return fetchArgs;
        },
        
      });
      request.hookXhr({
        onBeforeOpen: (args) => {
          let checkedInterfaceList = getCheckedInterfaceList(state.interfaceList)
          let url = args[1];
          if (isBackend(url)) return args;
          let path = `/`+getPartUrlByParam(url, 'path')
          let sceneId = ''
          checkedInterfaceList.forEach(i => {
            if(i.path === path) {
              i.sceneList.forEach(scene => {
                if(scene.checked) {
                  sceneId = scene._id
                }
              })
            }
          })
          sceneId && (args[0] = 'get')
          sceneId && (args[1] = `${getBackendConfig().mockBaseUrl}/api/app/scene/${sceneId}`)
          return args;
        },
        onBeforeSetRequestHeader: (args, config) => {
          let checkedInterfaceList = getCheckedInterfaceList(state.interfaceList)
          let url = config.originRequestInfo.url;
          if (isBackend(url)) return args;
          let path = `/`+getPartUrlByParam(url, 'path')
          let sceneId = ''
          checkedInterfaceList.forEach(i => {
            if(i.path === path) {
              i.sceneList.forEach(scene => {
                if(scene.checked) {
                  sceneId = scene._id
                }
              })
            }
          })
          if (sceneId) {
            return false
          }
          return args
        }
      });
  },
  onUnload(){}
})
