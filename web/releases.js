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
    "title": "从培训需求到师资选择，推进更顺畅",
    "sections": [
      {
        "status": "published",
        "version": "1.9.0",
        "recordedAt": "2026-09-07T03:32:04+08:00",
        "releasedAt": "2026-09-07T01:19:22+08:00",
        "timeSource": "recorded",
        "source": "v13-clarity-20260907-011000",
        "changes": [
          "按培训主题、参训对象、所属行业和学习目标逐项填写，也可直接粘贴客户原话，更轻松地整理培训需求。",
          "投标立项前即可比较讲师候选，查看匹配依据与待确认事项。每次以三位为推荐目标，人数不足或已有排课冲突会明确提示，最终安排由业务人员确认。",
          "老师档案增加常驻地区；专业条件相近时优先参考同城讲师，为线下培训的师资安排提供参考。线上授课不受地区排序影响。建档、上传简历与核对结果的操作顺序更清楚，已有档案无需重复创建。",
          "在项目页集中查看下一步待办、课程安排、评估与结算，相关事项可直接进入处理，减少跨模块查找。",
          "培训资料可按分类快速查找；更新记录集中展示产品进展，并提供作者联系与开源项目入口。",
          "改善首页主题展示的连续性：轻点不再中断播放，从更新记录返回时也能更顺畅地继续浏览。"
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
          "上传 PDF 或 PPTX 讲师简历，整理可识别的专业经历；无法自动识别的内容可人工补充，讲师简历不进入公开资料下载区。",
          "根据客户要求筛选讲师，结合匹配理由、课酬预算和系统已有排期，为沟通候选人提供依据。",
          "系统记录的授课场次、课时和已有评价与简历自述分开展示，便于了解讲师的实际履约情况。"
        ]
      },
      {
        "status": "published",
        "version": "1.8.1",
        "recordedAt": "2026-09-06T22:07:48+08:00",
        "timeSource": "deployment",
        "source": "weather-v12-20260906-214731",
        "changes": [
          "登录页和工作台可查看当前时间与城市天气，按访问网络估算城市，无需手动选择。",
          "天气信息标明服务来源；暂时无法获取天气或识别城市时，仍可正常登录和办理业务。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-22",
    "date": "2026-08-22",
    "title": "学习资料随时取用，批量管理更省心",
    "sections": [
      {
        "status": "published",
        "version": "1.7.0–1.7.2",
        "recordedAt": "2026-08-22T02:16:07+08:00",
        "timeSource": "merge_commit",
        "source": "7407ab7",
        "changes": [
          "客户和学员无需账号，即可搜索、按分类查找和下载已上架学习包，方便分享培训资料。",
          "系统管理员和业务管理员可在工作台统一上传、修改和上下架资料，让对外内容保持及时更新。",
          "一次选择或拖入最多 20 个文件，分别修改标题并查看进度；失败文件可单独重试，已成功的无需重复上传。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-13",
    "date": "2026-08-13",
    "title": "加强登录保护，让业务记录更可靠",
    "sections": [
      {
        "status": "published",
        "version": "1.6.0",
        "recordedAt": "2026-08-13T09:31:31+08:00",
        "timeSource": "commit",
        "source": "c9c48cd",
        "changes": [
          "加强登录会话与密码保护，现有账号可继续使用，无需重新注册。",
          "校验项目与来源需求、中标记录的关联，减少业务流转中的信息错配。",
          "保存时检查金额、记录编号和数据格式，减少错误数据进入业务台账。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-07",
    "date": "2026-08-07",
    "title": "每天该跟进什么，打开工作台就清楚",
    "sections": [
      {
        "status": "history",
        "version": "1.5.0",
        "recordedAt": null,
        "timeSource": "document_date",
        "source": "CHANGELOG",
        "changes": [
          "按紧急、关注和常规筛选待办，并直接进入下一步操作，优先跟进需要处理的工作。",
          "按日期查看近期课程，提前安排授课与交付准备。",
          "集中查看项目进度和回款概况，减少在多个模块之间来回核对。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-06",
    "date": "2026-08-06",
    "title": "常用功能与运营重点更易查找",
    "sections": [
      {
        "status": "history",
        "version": "1.1.0–1.4.0",
        "recordedAt": null,
        "timeSource": "document_date",
        "source": "CHANGELOG",
        "changes": [
          "手机端可通过常用导航切换业务模块，减少寻找操作入口的步骤。",
          "关键指标和事项状态更便于辨认，查看运营情况时更容易找到重点。"
        ]
      }
    ]
  },
  {
    "id": "day-2026-08-05",
    "date": "2026-08-05",
    "title": "完善登录与多设备使用体验",
    "sections": [
      {
        "status": "history",
        "version": "1.0.0",
        "recordedAt": null,
        "timeSource": "document_date",
        "source": "CHANGELOG",
        "changes": [
          "完善账号登录和工作台的使用体验，便于进入系统继续处理培训业务。",
          "改善电脑、平板和手机的页面适配，方便在不同设备上查看业务信息。"
        ]
      }
    ]
  },
  {
    "id": "initial",
    "date": "2026-07",
    "title": "培训运营，从需求到结算一站管理",
    "sections": [
      {
        "status": "history",
        "version": "0.1.0",
        "recordedAt": null,
        "timeSource": "document_month",
        "source": "CHANGELOG",
        "changes": [
          "在同一系统内管理培训需求、投标、项目和师资，并按发送、确认、完成的步骤推进课程交付。",
          "支持问卷收集与结果统计、分次回款、课酬计算和成本登记，集中保留业务与财务记录。",
          "区分系统管理员、业务管理员和只读用户，按角色开放操作权限，方便团队协作。"
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
