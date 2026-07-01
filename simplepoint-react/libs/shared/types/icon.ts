import * as AntIcons from "@ant-design/icons";
import * as React from "react";

// 🔹 直接从 `@ant-design/icons` 获取图标
export const createIcon = (name: string) => {
  // @ts-ignore
  const IconComponent = AntIcons[name]; // ✅ 根据 `name` 获取对应的图标组件
  return IconComponent ? React.createElement(IconComponent) : null; // 🔹 找不到则返回 `null`
};
