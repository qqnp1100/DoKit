<template>
  <div class="element-snippet-component">
    <div class="element-btn" @click="openCheck">
      <img
        :src="
          selectElement
            ? 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2264%22 height=%2264%22%3E%3Crect width=%2264%22 height=%2264%22 rx=%2214%22 fill=%22%232467eb%22/%3E%3Ctext x=%2232%22 y=%2243%22 text-anchor=%22middle%22 fill=%22white%22 font-size=%2230%22%3EDK%3C/text%3E%3C/svg%3E'
            : 'data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%2264%22 height=%2264%22%3E%3Crect width=%2264%22 height=%2264%22 rx=%2214%22 fill=%22%232467eb%22/%3E%3Ctext x=%2232%22 y=%2243%22 text-anchor=%22middle%22 fill=%22white%22 font-size=%2230%22%3EDK%3C/text%3E%3C/svg%3E'
        "
      />
    </div>
  </div>
</template>

<script>
import {
  toggleContainer,
  toggleHighlight,
  toggleElement,
} from "@dokit/web-core";
import { debounce } from "../../assets/util";
export default {
  data() {
    return {
      selectElement: false,
      checkCurrentElement: false,
      oldElement:null,
    };
  },
  computed: {
    state() {
      return this.$store.state;
    },
    showHighlightElement() {
      return this.state.showHighlightElement;
    },
    highlightElement() {
      return this.state.highlightElement;
    },
  },
  created() {
    this.onScroll = debounce(this.onScroll, 300);
  },
  watch: {
    showHighlightElement(val) {
      if (val) {
        document.body.addEventListener("click", this.elementClick, true);
        window.addEventListener("scroll", this.onScroll);
      } else {
        document.body.removeEventListener("click", this.elementClick, true);
        window.removeEventListener("scroll", this.onScroll);
      }
    },
  },
  methods: {
    openCheck() {
      this.selectElement = !this.selectElement;
      if (!this.showHighlightElement) {
        toggleContainer();
      }
      toggleHighlight();
    },
    elementClick(e) {
      e.preventDefault();
      e.stopImmediatePropagation();
      if (e.target !== this.highlightElement) {
        toggleElement(e.target);
      }
    },
    onScroll() {
      this.oldElement = this.highlightElement
      toggleElement(null);
      this.$nextTick(() => {
        toggleElement(this.oldElement);
      });
    },
  },
};
</script>

<style lang="less" scope>
.element-snippet-component {
  width: 100%;
  height: 40px;
  background: #f3f3f3;
  position: absolute;
  z-index: 100;
  left: 0;
  bottom: 0;
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  .element-btn {
    height: 100%;
    width: 100%;
    display: flex;
    align-items: center;
    justify-content: center;
    img {
      height: 20px;
    }
  }
}
</style>