ALTER TABLE dance_figure ADD COLUMN alternative_timing VARCHAR(255);

UPDATE dance_figure SET alternative_timing = '12&3, 123&' WHERE name = 'Double Reverse Spin' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Waltz');
UPDATE dance_figure SET alternative_timing = 'S, Q, &' WHERE name = 'Reverse Pivot' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Quickstep');
UPDATE dance_figure SET alternative_timing = 'SQQ, QQS' WHERE name = 'Running Finish' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Quickstep');
UPDATE dance_figure SET alternative_timing = 'SQQQQQSQQQQQQQQQQ' WHERE name = 'Corta Jaca' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = '12, 1&2' WHERE name = 'Natural Basic Movement (Alternative)' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = '12, 1&2' WHERE name = 'Outside Basic Movement' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = '12, 1&2' WHERE name = 'Progressive Basic Movement' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = '12, 1&2' WHERE name = 'Reverse Basic Movement' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = '1&2, SQQ' WHERE name = 'Reverse Turn' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = '12, 1&2' WHERE name = 'Side Basic Movement' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Samba');
UPDATE dance_figure SET alternative_timing = 'Q&Q, QQ' WHERE name = 'Promenade Walks (Slow and Quick) Development (Merengue)' AND dance_type_id = (SELECT id FROM dance_type WHERE name = 'Jive');
