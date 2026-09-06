/* Daily product notes. One entry per recorded calendar day; early month precision is retained.
 * recordedAt is a maintenance-record timestamp, NEVER an inferred deployment time.
 * Same-day edits update this entry instead of adding another release card.
 */
(function (root) {
  'use strict';
  const entries = [
  {
    "id": "day-2026-09-07",
    "date": "2026-09-07",
    "title": "全新工作界面与更直观的师资推荐",
    "sections": [
      {
        "status": "published",
        "version": "1.9.0",
        "recordedAt": "2026-09-07T01:27:10+08:00",
        "releasedAt": "2026-09-07T01:19:22+08:00",
        "timeSource": "recorded",
        "source": "v13-clarity-20260907-011000",
        "changes": [
          "更新浅色导航、图标、表格与项目页面，重点信息更清楚，手机端操作更方便。",
          "登录页采用动态银白背景与可拖动书本，培训运营等主题轮播，保留时间和自动城市天气；天气服务归属合并为一行。",
          "培训需求改为主题、对象、行业和目标的引导填写，清晰展示推荐依据与待确认事项。",
          "投标立项前即可推荐师资，以三位为推荐目标；增加常驻地区必填与同等专业条件下的就近优先，人数不足和档期冲突如实提示，不自动派课。",
          "培训资料采用分类导航与文件列表；更新记录按天整合，只展示已上线功能和可核实的更新时间。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-09-06",
    "date": "2026-09-06",
    "title": "讲师简历推荐与城市天气",
    "sections": [
      {
        "status": "published",
        "version": "1.8.0",
        "recordedAt": "2026-09-06T01:53:12+08:00",
        "timeSource": "commit",
        "source": "ac74e46",
        "changes": [
          "新增私有 PDF、PPTX 讲师简历上传、解析与人工核对，根据客户要求推荐讲师。",
          "展示匹配理由、预算与日期限制，区分简历自述和系统授课记录，不把缺失评价当成得分。"
        ]
      },
      {
        "status": "published",
        "version": "1.8.1",
        "recordedAt": "2026-09-06T22:07:48+08:00",
        "timeSource": "deployment",
        "source": "weather-v12-20260906-214731",
        "changes": [
          "登录页和工作台增加时间与城市天气，按访问网络自动识别城市，无需手动选择。",
          "展示天气来源；网络位置无法可靠识别时不猜测城市，天气不可用不影响登录。"
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
        "version": "1.6.0",
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
