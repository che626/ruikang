SET NAMES utf8mb4;
USE ruikang_fabric_qc;

UPDATE defect_type SET status = 0;

INSERT INTO defect_type (defect_code, defect_name, defect_name_en, defect_desc, display_color, sort_no, status)
VALUES
    ('oil_stain', '油渍', 'oil_stain', '油渍检测类别', '#facc15', 0, 1),
    ('ink_stain', '墨渍', 'ink_stain', '墨渍检测类别', '#22d3ee', 1, 1),
    ('hole', '破洞', 'hole', '破洞检测类别', '#fb923c', 2, 1),
    ('broken_yarn', '断纱', 'broken_yarn', '断纱检测类别', '#fb7185', 3, 1),
    ('wrinkle', '褶皱', 'wrinkle', '褶皱检测类别', '#38bdf8', 4, 1),
    ('foreign_matter', '异物', 'foreign_matter', '异物检测类别', '#34d399', 5, 1)
ON DUPLICATE KEY UPDATE
    defect_name = VALUES(defect_name),
    defect_name_en = VALUES(defect_name_en),
    defect_desc = VALUES(defect_desc),
    display_color = VALUES(display_color),
    sort_no = VALUES(sort_no),
    status = VALUES(status);
