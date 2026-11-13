package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** G7: which file references on an upload save must be ownership-checked. */
class UploadFileOwnershipTest {

  @Test
  void aNewOrChangedFileIsChecked() {
    assertTrue(AiOcrUploadManagementRepository.isNewFileReference(5L, null)); // new upload
    assertTrue(AiOcrUploadManagementRepository.isNewFileReference(7L, 5L)); // swapped file id
  }

  @Test
  void anUnchangedOrAbsentFileIsNot() {
    // A reviewer editing someone else's upload must not trip over its existing file.
    assertFalse(AiOcrUploadManagementRepository.isNewFileReference(5L, 5L));
    assertFalse(AiOcrUploadManagementRepository.isNewFileReference(null, 5L));
  }
}
