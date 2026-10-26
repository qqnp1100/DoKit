// 
// 功能: 对齐标尺
// 作者: foolbit (唐斌)
// 时间v2021-5-30
// 

import AlignRuler from './align-ruler.vue'
import {IndependPlugin} from '@dokit/web-core'

export default new IndependPlugin({
  nameZh: '对齐标尺',
  name: 'align-ruler',
  icon: 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2264%22 height=%2264%22%3E%3Crect width=%2264%22 height=%2264%22 rx=%2214%22 fill=%22%232467eb%22/%3E%3Ctext x=%2232%22 y=%2243%22 text-anchor=%22middle%22 fill=%22white%22 font-size=%2230%22%3EDK%3C/text%3E%3C/svg%3E',
  component: AlignRuler
})

