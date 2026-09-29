/** M04 v1: pure, in-memory catalog validation and course qualification. */
const OWN = (value, key) => Object.prototype.hasOwnProperty.call(value, key);
const OBJECT = value => value !== null && typeof value === 'object' && !Array.isArray(value) && (Object.getPrototypeOf(value) === Object.prototype || Object.getPrototypeOf(value) === null);
const CODE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const STATUSES = new Set(['certified', 'not_certified', 'unknown', 'revoked']);
const FIELDS = {
  catalog: ['schema_version', 'catalog_version', 'courses', 'teachers', 'certifications'],
  courses: ['course_code', 'course_name', 'active'],
  teachers: ['teacher_code', 'teacher_level', 'city'],
  certifications: ['teacher_code', 'course_code', 'status', 'source_ref', 'valid_from', 'valid_to'],
  request: ['course_code', 'as_of', 'accepted_levels', 'allowed_cities']
};

export function isISODate(value) {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const [year, month, day] = value.split('-').map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return year >= 1 && month >= 1 && month <= 12 && day >= 1 && day <= days[month - 1];
}

function checker(issues) {
  const issue = (table, row, field, code, message, severity = 'error') => {
    issues.push({ severity, table, row, field, code, message });
  };
  const fields = (record, table, row) => {
    for (const key of Object.keys(record)) {
      if (!FIELDS[table].includes(key)) issue(table, row, key, 'UNKNOWN_FIELD', '包含未约定字段，请仅保留编码契约字段。');
    }
  };
  const string = (record, field, table, row, max, nonblank = false) => {
    if (!OWN(record, field) || record[field] == null) { issue(table, row, field, 'REQUIRED', '缺少必填字段。'); return false; }
    const value = record[field];
    if (typeof value !== 'string') { issue(table, row, field, 'INVALID_TYPE', '必须是字符串，不自动转换数字。'); return false; }
    if (nonblank && !value) { issue(table, row, field, 'REQUIRED', '不能为空。'); return false; }
    if (value.length > max || /^[\s\p{Z}]|[\s\p{Z}]$/u.test(value) || /[\p{Cc}\p{Cf}]/u.test(value)) { issue(table, row, field, 'INVALID_TYPE', `文本不能超过 ${max} 字符或含控制、格式字符及首尾空白。`); return false; }
    return true;
  };
  const code = (record, field, table, row) => {
    if (!string(record, field, table, row, 64, true)) return false;
    if (!CODE.test(record[field])) { issue(table, row, field, 'INVALID_CODE', '编码须以英文字母或数字开头，仅使用字母、数字、点、下划线或连字符。'); return false; }
    return true;
  };
  const date = (record, field, table, row, empty = true) => {
    if (!string(record, field, table, row, 10, !empty)) return false;
    if ((empty && record[field] === '') || isISODate(record[field])) return true;
    issue(table, row, field, 'INVALID_DATE', '日期须为有效的 YYYY-MM-DD 格式。');
    return false;
  };
  return { issue, fields, string, code, date };
}

export function preview(payload) {
  const issues = [];
  const check = checker(issues);
  const result = {
    schema_version: 'm04_preview_v1', catalog_version: '', ready: false, issues,
    counts: { courses: 0, teachers: 0, certifications: 0 }, data: null
  };
  if (payload == null) payload = {};
  if (!OBJECT(payload)) {
    check.issue('catalog', 0, '', 'INVALID_TYPE', '目录必须是 JSON 对象。');
    return result;
  }
  check.fields(payload, 'catalog', 0);
  if (payload.schema_version !== 'm04_catalog_v1') check.issue('catalog', 0, 'schema_version', 'SCHEMA_VERSION', '仅支持 m04_catalog_v1。');
  if (check.string(payload, 'catalog_version', 'catalog', 0, 120, true)) result.catalog_version = payload.catalog_version;
  const arrays = {};
  for (const table of ['courses', 'teachers', 'certifications']) {
    if (!Array.isArray(payload[table])) {
      check.issue('catalog', 0, table, 'INVALID_TYPE', '必须是记录数组。');
      arrays[table] = [];
      continue;
    }
    result.counts[table] = payload[table].length;
    if (payload[table].length > 5000) {
      check.issue('catalog', 0, table, 'LIMIT', '单表最多接受 5000 行。');
      arrays[table] = [];
    } else arrays[table] = payload[table];
  }
  const teacherCodes = new Set(), courseCodes = new Set(), pairs = new Set();
  const unique = (set, value, table, row, field) => {
    if (set.has(value)) check.issue(table, row, field, 'DUPLICATE', '编码或教师课程组合重复；整批拒绝，不覆盖旧行。');
    else set.add(value);
  };
  for (const table of ['courses', 'teachers', 'certifications']) {
    Array.from(arrays[table]).forEach((record, index) => {
      const row = index + 1;
      if (!OBJECT(record)) { check.issue(table, row, '', 'INVALID_TYPE', '每行必须是 JSON 对象。'); return; }
      check.fields(record, table, row);
      if (table === 'courses') {
        if (check.code(record, 'course_code', table, row)) unique(courseCodes, record.course_code, table, row, 'course_code');
        check.string(record, 'course_name', table, row, 200, true);
        if (typeof record.active !== 'boolean') check.issue(table, row, 'active', 'INVALID_TYPE', 'active 必须是布尔值。');
      } else if (table === 'teachers') {
        if (check.code(record, 'teacher_code', table, row)) unique(teacherCodes, record.teacher_code, table, row, 'teacher_code');
        for (const field of ['teacher_level', 'city']) {
          if (check.string(record, field, table, row, 120) && !record[field]) check.issue(table, row, field, 'UNKNOWN_METADATA', '未提供此元数据；有对应筛选限制时不能通过。', 'warning');
        }
      } else {
        const teacherOK = check.code(record, 'teacher_code', table, row);
        const courseOK = check.code(record, 'course_code', table, row);
        if (teacherOK && courseOK) unique(pairs, JSON.stringify([record.teacher_code, record.course_code]), table, row, 'teacher_code,course_code');
        if (teacherOK && !teacherCodes.has(record.teacher_code)) check.issue(table, row, 'teacher_code', 'UNKNOWN_REFERENCE', '教师编码未在教师表中定义。');
        if (courseOK && !courseCodes.has(record.course_code)) check.issue(table, row, 'course_code', 'UNKNOWN_REFERENCE', '课程编码未在课程表中定义。');
        const statusOK = check.string(record, 'status', table, row, 40, true);
        if (statusOK && !STATUSES.has(record.status)) check.issue(table, row, 'status', 'INVALID_STATUS', '认证状态须为 certified、not_certified、unknown 或 revoked。');
        const sourceOK = check.string(record, 'source_ref', table, row, 240);
        if (sourceOK && record.status === 'certified' && !record.source_ref) check.issue(table, row, 'source_ref', 'SOURCE_REQUIRED', '已认证记录必须有编码来源引用。');
        const fromOK = check.date(record, 'valid_from', table, row);
        const toOK = check.date(record, 'valid_to', table, row);
        if (fromOK && toOK && record.valid_from && record.valid_to && record.valid_from > record.valid_to) check.issue(table, row, 'valid_to', 'DATE_ORDER', '结束日期不能早于开始日期。');
        if (record.status === 'unknown') check.issue(table, row, 'status', 'UNKNOWN_CERTIFICATION', '认证未知，不视为已认证。', 'warning');
        if (record.status === 'certified' && (record.valid_from === '' || record.valid_to === '')) check.issue(table, row, 'valid_from,valid_to', 'VALIDITY_UNSPECIFIED', '未完整提供有效期边界；空白不表示永久有效。', 'warning');
      }
    });
  }
  result.ready = !issues.some(item => item.severity === 'error');
  if (result.ready) {
    result.data = {
      schema_version: payload.schema_version, catalog_version: payload.catalog_version,
      courses: arrays.courses.map(row => ({ ...row })),
      teachers: arrays.teachers.map(row => ({ ...row })),
      certifications: arrays.certifications.map(row => ({ ...row }))
    };
  }
  return result;
}

export function qualify(payload, request) {
  const catalog = preview(payload);
  const issues = catalog.issues.map(row => ({ ...row }));
  const check = checker(issues);
  const result = {
    schema_version: 'm04_qualification_v1', ready: false, issues,
    catalog_version: catalog.catalog_version,
    course_code: '', as_of: '',
    eligible: [], gaps: [], ranking_status: 'not_configured'
  };
  if (request == null) request = {};
  if (!OBJECT(request)) {
    check.issue('request', 0, '', 'INVALID_TYPE', '资格请求必须是 JSON 对象。');
    return result;
  }
  check.fields(request, 'request', 0);
  const codeOK = check.code(request, 'course_code', 'request', 0);
  const dateOK = check.date(request, 'as_of', 'request', 0, false);
  if (codeOK) result.course_code = request.course_code;
  if (dateOK) result.as_of = request.as_of;
  for (const field of ['accepted_levels', 'allowed_cities']) {
    if (!Array.isArray(request[field])) { check.issue('request', 0, field, 'INVALID_TYPE', '筛选必须是字符串数组。'); continue; }
    if (request[field].length > 5000) { check.issue('request', 0, field, 'LIMIT', '筛选项最多 5000 个。'); continue; }
    const seen = new Set();
    Array.from(request[field]).forEach(value => {
      if (!check.string({ [field]: value }, field, 'request', 0, 120, true)) return;
      if (seen.has(value)) check.issue('request', 0, field, 'DUPLICATE', '筛选值不能重复。');
      seen.add(value);
    });
  }
  if (issues.some(row => row.severity === 'error')) return result;
  if (catalog.ready && codeOK) {
    const course = catalog.data.courses.find(row => row.course_code === request.course_code);
    if (!course) check.issue('request', 0, 'course_code', 'UNKNOWN_REFERENCE', '指定课程未在目录中定义。');
    else if (!course.active) check.issue('request', 0, 'course_code', 'COURSE_INACTIVE', '指定课程未启用，不能进行资格判定。');
  }
  if (issues.some(row => row.severity === 'error')) return result;
  const certifications = new Map(catalog.data.certifications.map(row => [JSON.stringify([row.teacher_code, row.course_code]), row]));
  for (const teacher of catalog.data.teachers) {
    const certification = certifications.get(JSON.stringify([teacher.teacher_code, request.course_code]));
    const reasons = [];
    const reason = (code, message) => reasons.push({ code, message });
    if (!certification) reason('CERTIFICATION_MISSING', '缺少该门课程的认证记录。');
    else {
      if (certification.status === 'unknown') reason('CERTIFICATION_UNKNOWN', '该门课程的认证状态未知。');
      if (certification.status === 'not_certified') reason('NOT_CERTIFIED', '该门课程尚未认证。');
      if (certification.status === 'revoked') reason('CERTIFICATION_REVOKED', '该门课程的认证已撤销。');
      if (certification.status === 'certified') {
        if (certification.valid_from && request.as_of < certification.valid_from) reason('NOT_YET_VALID', '该门课程的认证尚未生效。');
        if (certification.valid_to && request.as_of > certification.valid_to) reason('EXPIRED', '该门课程的认证已经过期。');
      }
    }
    if (request.accepted_levels.length) {
      if (!teacher.teacher_level) reason('LEVEL_UNKNOWN', '等级未知，无法通过已选等级限制。');
      else if (!request.accepted_levels.includes(teacher.teacher_level)) reason('LEVEL_NOT_ALLOWED', '等级不在所选精确允许列表中。');
    }
    if (request.allowed_cities.length) {
      if (!teacher.city) reason('CITY_UNKNOWN', '城市未知，无法通过已选城市限制。');
      else if (!request.allowed_cities.includes(teacher.city)) reason('CITY_NOT_ALLOWED', '城市不在所选精确允许列表中。');
    }
    const row = { ...teacher, eligible: reasons.length === 0, reasons, evidence: certification ? { ...certification } : null };
    (row.eligible ? result.eligible : result.gaps).push(row);
  }
  result.ready = true;
  return result;
}
