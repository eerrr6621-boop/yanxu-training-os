export function createDemoCatalog() {
  return {
    schema_version: 'm04_catalog_v1', catalog_version: 'SYNTHETIC-M04-20260921',
    courses: [
      { course_code: 'DEMO-C01', course_name: '家庭预算（合成）', active: true },
      { course_code: 'DEMO-C02', course_name: '风险识别（合成）', active: true }
    ],
    teachers: [
      { teacher_code: 'DEMO-T01', teacher_level: 'L1', city: '演示甲城' },
      { teacher_code: 'DEMO-T02', teacher_level: 'L2', city: '演示乙城' },
      { teacher_code: 'DEMO-T03', teacher_level: '', city: '' }
    ],
    certifications: [
      { teacher_code: 'DEMO-T01', course_code: 'DEMO-C01', status: 'certified', source_ref: 'DEMO-SRC-01', valid_from: '2026-01-01', valid_to: '2026-12-31' },
      { teacher_code: 'DEMO-T02', course_code: 'DEMO-C01', status: 'unknown', source_ref: '', valid_from: '', valid_to: '' },
      { teacher_code: 'DEMO-T03', course_code: 'DEMO-C02', status: 'certified', source_ref: 'DEMO-SRC-02', valid_from: '2026-01-01', valid_to: '2026-08-31' }
    ]
  };
}

export function createDemoRequest() {
  return { course_code: 'DEMO-C01', as_of: '2026-09-21', accepted_levels: [], allowed_cities: [] };
}
