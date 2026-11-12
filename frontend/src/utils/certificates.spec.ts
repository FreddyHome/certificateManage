import { describe, it, expect } from 'vitest';
import { filteredCertificates } from './certificates';
describe('certificate filtering',()=>{it('keeps renewed same-CN certificates independent and orders expiry',()=>{const rows=[{id:2,name:'same',notAfter:'2027-01-01',purpose:'AS2',customer:'A'},{id:1,name:'same',notAfter:'2026-01-01',purpose:'AS2',customer:'A'}];expect(filteredCertificates(rows,'same','AS2').map(r=>r.id)).toEqual([1,2]);expect(filteredCertificates(rows,'missing','AS2')).toEqual([])});});
