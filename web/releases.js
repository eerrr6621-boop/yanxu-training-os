/* Daily product notes. One entry per recorded calendar day; early month precision is retained.
 * recordedAt is a maintenance-record timestamp, NEVER an inferred deployment time.
 * Same-day edits update this entry instead of adding another release card.
 */
(function (root) {
  'use strict';
  const entries = [
  {
    "id": "day-2026-09-06",
    "date": "2026-09-06",
    "title": "师资推荐与全新工作体验",
    "sections": [
      {
        "status": "published",
        "version": "1.8.0 · V12 R2",
        "recordedAt": "2026-09-06T01:53:12+08:00",
        "timeSource": "commit",
        "source": "ac74e46",
        "changes": [
          "新增私有 PDF、PPTX 讲师简历上传、解析与人工核对，根据客户要求推荐讲师。",
          "展示匹配理由、预算与日期限制，区分简历自述和系统授课记录，不把缺失评价当成得分。"
        ]
      },
      {
        "status": "preview",
        "version": "V13 · 当日整合",
        "recordedAt": "2026-09-06T21:23:35+08:00",
        "timeSource": "recorded",
        "source": "本次日更记录整理",
        "changes": [
          "师资推荐前置到投标立项之前，以培训主题、对象、行业和目标引导填空，推荐依据与待确认事项分层呈现。",
          "默认争取推荐至少三位讲师；增加必填常驻地区与同等专业条件下的就近优先，不足、未知地区或排期冲突如实提示，不自动派课。",
          "统一工作台、导航、图标与列表层级，重排项目详情和师资页面，公开资料中心改为清晰的分类与文件列表。",
          "登录页采用银白光面背景与可拖动的展开书本，四组定制标题轮播；新增时间和可选的自动 IP 城市天气，未配置时不展示虚假天气。",
          "放大首页公共入口并加入 NEW 提示，登录按钮文字居中；往期更新按天合并、去除重复说明，并展示可核实的时分秒。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-22",
    "date": "2026-08-22",
    "title": "公开培训资料与批量管理",
    "sections": [
      {
        "status": "published",
        "version": "1.7.0–1.7.2",
        "recordedAt": "2026-08-22T02:16:07+08:00",
        "timeSource": "merge_commit",
        "source": "7407ab7",
        "changes": [
          "新增公开资料中心，外部访客无需账号即可搜索、按分类查找和下载已上架学习包。",
          "系统管理员和业务管理员可上传、编辑、上下架资料，工作台提供明确管理入口。",
          "支持一次选择最多 20 个文件、分别编辑名称和查看上传进度；失败项可单独重试，成功文件不重复上传。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-13",
    "date": "2026-08-13",
    "title": "研序品牌与科技展示页",
    "sections": [
      {
        "status": "published",
        "version": "1.6.0 · V10",
        "recordedAt": "2026-08-13T09:31:31+08:00",
        "timeSource": "commit",
        "source": "c9c48cd",
        "changes": [
          "更新研序品牌、科技展示型登录页与蓝紫视觉语言。",
          "完善不同角色的操作体验以及手机、平板布局。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-07",
    "date": "2026-08-07",
    "title": "以待办为中心的今日运营",
    "sections": [
      {
        "status": "history",
        "version": "1.5.0",
        "recordedAt": null,
        "timeSource": "document_date",
        "source": "CHANGELOG",
        "changes": [
          "按紧急、关注和常规梳理运营事项，直接进入下一步操作。",
          "整合近期课程、项目进度与回款概况，便于查看当天工作重点。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-06",
    "date": "2026-08-06",
    "title": "轻量工作面与更清楚的反馈",
    "sections": [
      {
        "status": "history",
        "version": "1.1.0–1.4.0",
        "recordedAt": null,
        "timeSource": "document_date",
        "source": "CHANGELOG",
        "changes": [
          "统一浅色工作面、图表配色、标题与关键指标层级，优化待办状态和风险提示。",
          "增加移动导航与快捷入口，改进登录交互和前端资源更新机制。",
          "补充指标变化、滚动反馈与交互光影，尊重系统减少动态效果设置。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-05",
    "date": "2026-08-05",
    "title": "培训运营工作台",
    "sections": [
      {
        "status": "history",
        "version": "1.0.0",
        "recordedAt": null,
        "timeSource": "document_date",
        "source": "CHANGELOG",
        "changes": [
          "整理需求、投标立项、交付和结算的工作台界面。",
          "完善登录表单、移动布局与版本回退检查。"
        ]
      }
    ]
  },
  {
    "id": "initial",
    "date": "2026-07",
    "title": "最初的全流程版本",
    "sections": [
      {
        "status": "history",
        "version": "0.1.0",
        "recordedAt": null,
        "timeSource": "document_month",
        "source": "CHANGELOG",
        "changes": [
          "建立需求、投标立项、排期、评估、财务与师资基础模块。",
          "引入系统管理员、业务管理员和只读角色。"
        ]
      }
    ]
  }
];
  root.YanxuReleases = Object.freeze(entries.map(entry => Object.freeze({
    ...entry, sections: Object.freeze(entry.sections.map(section => Object.freeze({
      ...section, changes: Object.freeze(section.changes)
    })))
  })));
})(typeof window === 'undefined' ? globalThis : window);
