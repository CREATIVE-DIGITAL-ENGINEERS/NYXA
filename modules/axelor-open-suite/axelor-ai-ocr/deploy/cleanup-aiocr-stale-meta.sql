-- ============================================================================
-- Path B — remove STALE AiOcrDocument meta from an EXISTING Axelor DB.
-- Safe for a live /test or prod DB: touches ONLY orphaned meta rows for the
-- removed AiOcrDocument review workflow. Does NOT drop business data.
--
-- Context: the AiOcrDocument domain/views/menu were deleted in "AI-OCR Phase 1".
-- Axelor never prunes meta whose XML was removed, so these rows linger and point
-- at a model class that no longer exists -> Internal Server Error on open.
--
-- RUN INSIDE A TRANSACTION, after a DB backup. Review the SELECT output first.
-- ============================================================================

BEGIN;

-- 1) Inspect what will be removed (review before committing) ------------------
SELECT 'menu'   AS kind, name FROM meta_menu   WHERE name = 'account-root-ai-ocr-docs'
UNION ALL
SELECT 'action', name FROM meta_action WHERE name LIKE 'action-ai-ocr-document-%'
UNION ALL
SELECT 'view',   name FROM meta_view   WHERE name LIKE 'ai-ocr-document-%'
UNION ALL
SELECT 'select', name FROM meta_select WHERE name LIKE 'ai.ocr.document.%';

-- 2) Clear m2m group links first (FK safety) ---------------------------------
DELETE FROM meta_menu_groups
 WHERE meta_menu_id IN (SELECT id FROM meta_menu WHERE name = 'account-root-ai-ocr-docs');
DELETE FROM meta_view_groups
 WHERE meta_view_id IN (SELECT id FROM meta_view WHERE name LIKE 'ai-ocr-document-%');

-- 3) Delete the menu (it references the action) ------------------------------
DELETE FROM meta_menu WHERE name = 'account-root-ai-ocr-docs';

-- 4) Delete the actions (view / approve / reject / defaults) ------------------
DELETE FROM meta_action
 WHERE name IN (
   'action-ai-ocr-document-view',
   'action-ai-ocr-document-approve',
   'action-ai-ocr-document-reject',
   'action-ai-ocr-document-defaults');

-- 5) Delete the grid/form views ----------------------------------------------
DELETE FROM meta_view
 WHERE name IN (
   'ai-ocr-document-grid',
   'ai-ocr-document-form',
   'ai-ocr-document-line-grid',
   'ai-ocr-document-line-form');

-- 6) Delete the selections (items first, then the select) --------------------
DELETE FROM meta_select_item
 WHERE select_id IN (
   SELECT id FROM meta_select
    WHERE name IN ('ai.ocr.document.status.select','ai.ocr.document.vat.rate.select'));
DELETE FROM meta_select
 WHERE name IN ('ai.ocr.document.status.select','ai.ocr.document.vat.rate.select');

-- NOTE: the orphaned AiOcrDocument / AiOcrDocumentLine *entity* meta
-- (meta_model / meta_field rows) and the physical ai_ocr_document* tables are
-- harmless and intentionally left in place (dropping tables = data change).
-- They can be pruned later if desired.

-- Review the row counts above, then:
--   COMMIT;   -- to apply
--   ROLLBACK; -- to abort
COMMIT;
