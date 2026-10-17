-- Synthetic evaluation data for issue #331 (semantic search relevance).
-- Generated; do not edit by hand. Everything is synthetic and for the DEV database only.
-- Undo with revert.sql. After running this, rebuild the knowledge index (Admin > Rebuild).
BEGIN;

-- 1. Figure details (technique notes and positions)
CREATE TEMP TABLE seed_figure(dance text, name text, start_pos text, end_pos text, notes text) ON COMMIT DROP;
INSERT INTO seed_figure VALUES
  ($q$Waltz$q$,$q$Natural Turn$q$,$q$Facing Diagonal Wall$q$,$q$Backing Diagonal Centre$q$,$q$Rise at the end of 1, maintained on 2, lowering at the end of 3. Body turn begins on 1 with CBM and the left side stays long through the turn. Keep the head weight to the left and the topline stretched; do not drop the head forward as you rise.$q$),
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$Facing Diagonal Centre$q$,$q$Backing Diagonal Wall$q$,$q$Mirror of the Natural Turn: left side of the lady stays long, man leads the turn from the torso, not the arms. Sway to the left on 2, no sway on 1 and 3. Lower on the end of 3 through the ankles and knees.$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$Facing Diagonal Wall$q$,$q$Promenade Position$q$,$q$Step 3 is crossed behind with the toe turned out and the head already moving to the right. Rise on 2, lower after 3. Keep the frame closed until the lady opens to Promenade Position, then show a clear lengthening of the left side.$q$),
  ($q$Waltz$q$,$q$Hesitation Change$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$Hesitation on step 3: the weight is taken on the foot but the body keeps rising, so the lowering is delayed to the end of count 3. Do not let the head fall as the rise settles.$q$),
  ($q$Waltz$q$,$q$Telemark (Closed Telemark)$q$,$q$Facing Diagonal Centre$q$,$q$Facing Diagonal Wall$q$,$q$Heel turn on step 2 with the man turning on his left heel; keep the shoulders level and the head weight left. Rise through steps 2 and 3.$q$),
  ($q$Waltz$q$,$q$Fallaway Reverse and Slip Pivot$q$,$q$Facing Diagonal Centre$q$,$q$Backing Diagonal Wall$q$,$q$Fallaway with the head to the left, open topline with the lady's head turned strongly right. The slip pivot needs the man to stay compact and not over-rotate on the backward step.$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$Facing Diagonal Wall$q$,$q$Promenade Position$q$,$q$No rise and fall: the knees stay slightly flexed and the walk is flat. The head snap to Promenade Position is quick and sharp on the &. Keep the frame compact and the lady's left side long without lifting the ribcage.$q$),
  ($q$Tango$q$,$q$Progressive Link$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$Staccato action: the foot is placed slightly across and the knee reaches before the weight arrives. Avoid stepping heavily; think of a stalking cat.$q$),
  ($q$Tango$q$,$q$Natural Twist Turn$q$,$q$Facing Diagonal Centre$q$,$q$Facing Diagonal Wall$q$,$q$The man stays on his supporting foot while the lady twists around him. Maintain the contra-body position and a stable topline; the head must not drop during the twist.$q$),
  ($q$Tango$q$,$q$Open Reverse, Lady Outside (Open Finish)$q$,$q$Facing Diagonal Centre$q$,$q$Facing Diagonal Wall$q$,$q$Sharp CBM on step 1, ending with the lady outside. Slow-slow-quick-quick-slow rhythm; the head snap on the quick steps is small and controlled.$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$Slow-quick-quick. Heel lead on step 1 with a long smooth stride; step 3 outside partner in CBMP. Rise gradually from the end of 1 and lower on 3 without a bounce. Keep the head weight to the left.$q$),
  ($q$Foxtrot$q$,$q$Reverse Turn$q$,$q$Facing Diagonal Centre$q$,$q$Backing Diagonal Wall$q$,$q$Heel turn on step 3, with the man's left shoulder leading. Keep the body flight smooth and the topline stretched upwards; the head should stay left and not dip as the lowering happens.$q$),
  ($q$Foxtrot$q$,$q$Three Step$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$Three steps with the long-short-short timing. Heel lead, smooth travel. A common fault is rushing step 3; stay on the foot until the end of the count.$q$),
  ($q$Foxtrot$q$,$q$Natural Weave$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$The weave is a flow of curved, outside-partner steps on a long arc. The man must allow the lady to curve around him with soft knees and a continuous swing.$q$),
  ($q$Quickstep$q$,$q$Natural Turn (at a Corner)$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$Fast travelling turn on a corner. Use a lot of foot and ankle action and a strong swing into the diagonal. Keep the head up and the left side long to control speed.$q$),
  ($q$Quickstep$q$,$q$Progressive Chasse$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$Slow-quick-quick-slow with the chasse steps closing lightly. Rise on the quick steps without bouncing, and lower on the last slow.$q$),
  ($q$Quickstep$q$,$q$Running Right Turn$q$,$q$Facing Diagonal Wall$q$,$q$Facing Diagonal Wall$q$,$q$A curved running finish with four small quick steps; the body continues to turn right. The drive for the finish comes from the standing foot, not from hopping.$q$),
  ($q$Quickstep$q$,$q$Quick Open Reverse$q$,$q$Facing Diagonal Centre$q$,$q$Facing Diagonal Wall$q$,$q$Quick open reverse turn with the lady outside partner at the end. Maintain the frame and avoid taking the lady's weight with the arms.$q$),
  ($q$Viennese Waltz$q$,$q$Reverse Turn$q$,$q$Facing Diagonal Centre$q$,$q$Backing Diagonal Wall$q$,$q$Six steps at a fast tempo, with rise on 1, 3, 4 and 6 and the feet closing on 3 and 6. Keep the left side long as the turn travels round the floor; do not drop the head as the speed builds.$q$),
  ($q$Viennese Waltz$q$,$q$Reverse Fleckerl$q$,$q$Backing Line of Dance$q$,$q$Backing Line of Dance$q$,$q$Turning on the spot with small steps. Heel turns by the man while the lady travels around him; keep the head weight left and the body compact.$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$Open Position$q$,$q$Open Position$q$,$q$A fan followed by a lock-step type finish. The man leads the lady into a fan position then brings her through with a hockey stick curve. Cuban hip action on the forward walks and a clear Cha Cha Cha rhythm 2 3 4&1.$q$),
  ($q$Cha Cha$q$,$q$Fan$q$,$q$Closed Position$q$,$q$Fan Position$q$,$q$The man steps back with a check on the left foot while the lady turns across the line of dance into fan position. Hold the position slightly stronger than the previous step.$q$),
  ($q$Cha Cha$q$,$q$Alemana Development (R to R Hand Hold)$q$,$q$Open Position$q$,$q$Open Position$q$,$q$Right hand to right hand hold. The lady turns under the arm with a clear spiral action; the man steps forward with a strong lead from the body.$q$),
  ($q$Cha Cha$q$,$q$Spot Turns to L or R$q$,$q$Open Position$q$,$q$Open Position$q$,$q$Turning on the spot with hip action. The chasse is compact and the weight is on the ball of the foot; use the head to spot the turn.$q$),
  ($q$Cha Cha$q$,$q$Time Steps (Basic in Place, Side Basic)$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$A rocking action with the weight changing on the ball of the foot. Keep the knees soft and use Cuban motion on the steps.$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$Closed Position$q$,$q$Fan Position$q$,$q$The lady opens out to fan position while the man checks back with a stable posture. Hold the check for the full count with expression through the arms.$q$),
  ($q$Rumba$q$,$q$Alemana$q$,$q$Open Position$q$,$q$Open Position$q$,$q$The lady turns under the man's raised hand in three steps. Slow-quick-quick-slow. Keep the connection through the hand and avoid pulling the lady's arm.$q$),
  ($q$Rumba$q$,$q$Hockey Stick$q$,$q$Open Position$q$,$q$Open Position$q$,$q$A hockey stick shape created by the lady's line passing the man with a lock. The man guides rather than pushes; slow walks with delayed hip action.$q$),
  ($q$Rumba$q$,$q$Natural Top$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$A spot turn to the right. The lady makes a full turn while the man stays compact and leads the turn from the body. Slow walks with Cuban motion between turns.$q$),
  ($q$Rumba$q$,$q$Basic Movements (Closed, Open, In Place and Alternative)$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$Slow, quick, quick with delayed hip action. The weight must be fully transferred before the hip movement, with the knee straight on the standing leg and bent on the free leg.$q$),
  ($q$Samba$q$,$q$Natural Roll$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$A rolling action with a travelling turn. Samba bounce action through the knees, with a rhythm of 1 a 2 and the weight remaining forward over the ball of the foot.$q$),
  ($q$Samba$q$,$q$Closed Volta$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$A series of short steps on the balls of the feet with the knees together on the closes and a subtle bounce. Keep the body upright and the head still.$q$),
  ($q$Samba$q$,$q$Bota Fogos To PP and CPP (Promenade Botafogos)$q$,$q$Promenade Position$q$,$q$Promenade Position$q$,$q$A forward step across with the free leg turned out, then a rocking action. Use a travelling action with soft knees; the hip action is smooth rather than sharp.$q$),
  ($q$Samba$q$,$q$Whisk to Right and Left Underarm Turn (Volta Spot Turn to R and L for Lady)$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$The lady makes a volta spot turn under the man's raised arm. The man's whisk stays compact and led by the body, not the hand.$q$),
  ($q$Jive$q$,$q$Basic in Place$q$,$q$Open Position$q$,$q$Open Position$q$,$q$Rock step and chasse with the bounce action in the knees. Weight on the ball of the foot; the feet stay close to the floor with quick, light movements.$q$),
  ($q$Jive$q$,$q$Chicken Walks$q$,$q$Open Position$q$,$q$Open Position$q$,$q$Flicks of the free foot back and up with a rotation of the knees. The man stays low and relaxed; the flick is from the knee, not a kick.$q$),
  ($q$Jive$q$,$q$Whip Throwaway$q$,$q$Open Position$q$,$q$Open Position$q$,$q$The man whips the lady into a throwaway; maintain a strong left hand connection and a compact rock before the throw. Rhythm Slow Slow Quick Quick Slow.$q$),
  ($q$Paso Doble$q$,$q$Appel$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$A stamping action with the foot placed flat and sharp, ending in a strong, proud pose. Keep the weight over the supporting foot and the chest lifted.$q$),
  ($q$Paso Doble$q$,$q$Sur Place$q$,$q$Closed Position$q$,$q$Closed Position$q$,$q$Marching in place with a strong, proud topline. The ribcage is lifted and the shoulders are down; keep the head high and the walk staccato.$q$),
  ($q$Paso Doble$q$,$q$Separation$q$,$q$Closed Position$q$,$q$Open Position$q$,$q$The man and lady separate with Spanish lines. Keep the arms high with the elbows soft; stay visually connected through the eyes.$q$);
UPDATE dance_figure f
   SET notes = s.notes,
       starting_position = COALESCE(f.starting_position, s.start_pos),
       ending_position = COALESCE(f.ending_position, s.end_pos),
       version = f.version + 1
  FROM seed_figure s
  JOIN dance_type t ON t.name = s.dance
 WHERE f.dance_type_id = t.id AND f.name = s.name AND f.notes IS NULL;

-- 2. Step tables for a few figures
CREATE TEMP TABLE seed_step(dance text, fig text, role text, n int, timing text, foot text, action text, footwork text, alignment text, turn text) ON COMMIT DROP;
INSERT INTO seed_step VALUES
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$LEADER$q$,1,$q$1$q$,$q$LF$q$,$q$LF forward$q$,$q$HT$q$,$q$DC$q$,$q$commence to turn left$q$),
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$LEADER$q$,2,$q$2$q$,$q$RF$q$,$q$RF to side and slightly forward$q$,$q$T$q$,$q$DW$q$,$q$1/4 to L$q$),
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$LEADER$q$,3,$q$3$q$,$q$LF$q$,$q$LF closes to RF$q$,$q$TH$q$,$q$BDW$q$,$q$1/8 to L$q$),
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$FOLLOWER$q$,1,$q$1$q$,$q$RF$q$,$q$RF back$q$,$q$TH$q$,$q$BC$q$,$q$commence to turn left$q$),
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$FOLLOWER$q$,2,$q$2$q$,$q$LF$q$,$q$LF back and to side$q$,$q$T$q$,$q$BDW$q$,$q$1/4 to L$q$),
  ($q$Waltz$q$,$q$Reverse Turn$q$,$q$FOLLOWER$q$,3,$q$3$q$,$q$RF$q$,$q$RF closes to LF$q$,$q$TH$q$,$q$DW$q$,$q$1/8 to L$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$LEADER$q$,1,$q$1$q$,$q$LF$q$,$q$LF forward$q$,$q$HT$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$LEADER$q$,2,$q$2$q$,$q$RF$q$,$q$RF to side$q$,$q$T$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$LEADER$q$,3,$q$3$q$,$q$LF$q$,$q$LF crossed behind RF, toe turned out$q$,$q$TFlat$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$FOLLOWER$q$,1,$q$1$q$,$q$RF$q$,$q$RF back$q$,$q$TH$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$FOLLOWER$q$,2,$q$2$q$,$q$LF$q$,$q$LF to side$q$,$q$T$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Waltz$q$,$q$Whisk$q$,$q$FOLLOWER$q$,3,$q$3$q$,$q$RF$q$,$q$RF crossed behind LF$q$,$q$TFlat$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$LEADER$q$,1,$q$S$q$,$q$LF$q$,$q$LF forward, curving slightly left$q$,$q$HT$q$,$q$DW$q$,$q$slight turn to L$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$LEADER$q$,2,$q$S$q$,$q$RF$q$,$q$RF to side and slightly forward in Promenade Position$q$,$q$H$q$,$q$PDC$q$,$q$no turn$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$LEADER$q$,3,$q$Q$q$,$q$LF$q$,$q$LF forward and across in PP$q$,$q$HT$q$,$q$PDC$q$,$q$no turn$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$FOLLOWER$q$,1,$q$S$q$,$q$RF$q$,$q$RF back$q$,$q$TH$q$,$q$BDC$q$,$q$slight turn to L$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$FOLLOWER$q$,2,$q$S$q$,$q$LF$q$,$q$LF to side and slightly forward in PP$q$,$q$H$q$,$q$PDC$q$,$q$no turn$q$),
  ($q$Tango$q$,$q$Closed Promenade$q$,$q$FOLLOWER$q$,3,$q$Q$q$,$q$RF$q$,$q$RF forward and across in PP$q$,$q$HT$q$,$q$PDC$q$,$q$no turn$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$LEADER$q$,1,$q$S$q$,$q$RF$q$,$q$RF forward, heel lead$q$,$q$HT$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$LEADER$q$,2,$q$Q$q$,$q$LF$q$,$q$LF forward, preparing to step outside partner$q$,$q$T$q$,$q$DW$q$,$q$slight turn to R$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$LEADER$q$,3,$q$Q$q$,$q$RF$q$,$q$RF forward in CBMP, outside partner$q$,$q$TH$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$FOLLOWER$q$,1,$q$S$q$,$q$LF$q$,$q$LF back, toe-heel$q$,$q$TH$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$FOLLOWER$q$,2,$q$Q$q$,$q$RF$q$,$q$RF back, curving to right$q$,$q$T$q$,$q$DW$q$,$q$slight turn to R$q$),
  ($q$Foxtrot$q$,$q$Feather Step$q$,$q$FOLLOWER$q$,3,$q$Q$q$,$q$LF$q$,$q$LF back, in CBMP$q$,$q$TH$q$,$q$DW$q$,$q$no turn$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$LEADER$q$,1,$q$2$q$,$q$LF$q$,$q$LF forward, with Cuban action$q$,$q$TH$q$,$q$FLOD$q$,$q$no turn$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$LEADER$q$,2,$q$3$q$,$q$RF$q$,$q$Replace weight back to RF$q$,$q$Ball$q$,$q$FLOD$q$,$q$no turn$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$LEADER$q$,3,$q$4&1$q$,$q$LF$q$,$q$Chasse L R L, closing the Lady into fan position$q$,$q$BallBall$q$,$q$FLOD$q$,$q$1/8 to R$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$FOLLOWER$q$,1,$q$2$q$,$q$RF$q$,$q$RF forward, with Cuban action$q$,$q$TH$q$,$q$FLOD$q$,$q$no turn$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$FOLLOWER$q$,2,$q$3$q$,$q$LF$q$,$q$Replace weight back to LF$q$,$q$Ball$q$,$q$FLOD$q$,$q$no turn$q$),
  ($q$Cha Cha$q$,$q$Hockey Stick$q$,$q$FOLLOWER$q$,3,$q$4&1$q$,$q$RF$q$,$q$Chasse R L R, curving to the left under the arm$q$,$q$BallBall$q$,$q$FLOD$q$,$q$1/2 to L$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$LEADER$q$,1,$q$2$q$,$q$LF$q$,$q$LF forward with a check, Lady turning across$q$,$q$BF$q$,$q$LOD$q$,$q$no turn$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$LEADER$q$,2,$q$3$q$,$q$RF$q$,$q$Replace weight back to RF$q$,$q$Ball$q$,$q$LOD$q$,$q$no turn$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$LEADER$q$,3,$q$4&1$q$,$q$LF$q$,$q$LF closes to RF, small step$q$,$q$Ball$q$,$q$LOD$q$,$q$no turn$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$FOLLOWER$q$,1,$q$2$q$,$q$RF$q$,$q$RF back, turning$q$,$q$Ball$q$,$q$LOD$q$,$q$1/4 to R$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$FOLLOWER$q$,2,$q$3$q$,$q$LF$q$,$q$LF forward, replacing weight$q$,$q$Ball$q$,$q$LOD$q$,$q$no turn$q$),
  ($q$Rumba$q$,$q$Fan$q$,$q$FOLLOWER$q$,3,$q$4&1$q$,$q$RF$q$,$q$RF to side$q$,$q$Ball$q$,$q$LOD$q$,$q$no turn$q$);
INSERT INTO dance_figure_step_set (id, dance_figure_id, name, is_default)
SELECT gen_random_uuid(), f.id, 'Default', true
  FROM dance_figure f
  JOIN dance_type t ON t.id = f.dance_type_id
 WHERE (t.name, f.name) IN (SELECT dance, fig FROM seed_step)
   AND NOT EXISTS (SELECT 1 FROM dance_figure_step_set x WHERE x.dance_figure_id = f.id);
INSERT INTO dance_figure_step (id, step_number, timing, role, foot, action, footwork, alignment, amount_of_turn, dance_figure_step_set_id)
SELECT gen_random_uuid(), s.n, s.timing, s.role, s.foot, s.action, s.footwork, s.alignment, s.turn, ss.id
  FROM seed_step s
  JOIN dance_type t ON t.name = s.dance
  JOIN dance_figure f ON f.dance_type_id = t.id AND f.name = s.fig
  JOIN dance_figure_step_set ss ON ss.dance_figure_id = f.id AND ss.is_default
 WHERE NOT EXISTS (SELECT 1 FROM dance_figure_step x WHERE x.dance_figure_step_set_id = ss.id);

-- 3. Notes (materials)
INSERT INTO material (id, name, description, dance_type_id, owner_id, visibility, created_at, version)
SELECT v.id::uuid, v.name, v.descr, (SELECT id FROM dance_type WHERE name = NULLIF(v.dance, '')), (SELECT id FROM app_user WHERE username = v.owner), v.vis, now(), 0
  FROM (VALUES
  ($q$76a06b4c-6e22-508e-a164-cee4717424e5$q$,$q$Waltz — keep the head weight left$q$,$q$<div>In closed position the <strong>head weight goes to the left</strong>, over the left side. Whenever I rise into step 2 the chin drifts down and forward and the topline collapses.</div><div><br></div><div>Fix from the last lesson: look over the partner's right shoulder, keep the neck long and think of the crown of the head reaching up, not forward.</div>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$cfc019e9-7612-5f31-b5a7-0e3a3669c557$q$,$q$Dropping the head in the lowering$q$,$q$<div>Coach noticed my <strong>head dropping</strong> on the lowering at the end of count 3 in the Natural Turn. It makes the frame look unstable and kills the rise on the next bar.</div><ul><li>Lower through the ankles, knees and then the hip, <em>not</em> through the neck</li><li>Keep the eyes level, on a point on the far wall</li></ul>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$805e24bf-d16c-5f4b-ae8f-33c90b1cb8fa$q$,$q$Left side long — lady's frame$q$,$q$<div>Lady's left side must stay <strong>long and stretched</strong> from the hip to the elbow. Hardest part is keeping it long while turning.</div><div><br></div><div>Drill: stand against the wall, reach the left arm up and away and feel the stretch along the rib cage, then keep that feeling in hold.</div>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$4183d3e6-0a8e-5ce3-9c68-ead4de9eefac$q$,$q$Hesitation change — delayed lowering$q$,$q$<div>On step 3 the weight is taken but the body keeps rising. Lowering is delayed to the end of 3.</div><ol><li>Stay on the toe through the hesitation</li><li>Keep the rise from dropping suddenly</li></ol>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$2f8d2fe7-6b36-5c57-9abb-315bc92a5ae3$q$,$q$Waltz rise and fall — lesson notes$q$,$q$<div>Rise starts at the <strong>end of count 1</strong>, is full on 2, and the lowering happens on the end of 3. The foot rises first, then the body.</div><div><br></div><div>Avoid bouncing. In Natural Turn feel the rise as a continuous lift, not as three separate bumps.</div>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$13c81077-de9d-5f33-a39d-74219e67da00$q$,$q$Slow foxtrot — walking with heel lead$q$,$q$<div>Heel leads on every forward step, with the foot gliding close to the floor. <strong>Long strides</strong> need a lowered body and a gradual rise.</div><ul><li>Feather Step: S Q Q</li><li>Stay flat and quiet on the shoulders</li></ul>$q$,$q$Foxtrot$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$5976f255-0990-5c3a-a06a-4ff57c60e28d$q$,$q$Feather Step — outside partner on step 3$q$,$q$<div>Step 3 goes <strong>outside partner</strong> in CBMP. The man must curve slightly right on step 2 so that the lady is not blocked.</div><div>Do not rush: wait until the end of the count.</div>$q$,$q$Foxtrot$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$c6ead78b-3d25-5a47-bb1e-dbf83218daa9$q$,$q$Foxtrot — head and topline$q$,$q$<div>The lady's <strong>topline</strong> is the difference between a good and a great Foxtrot. Head to the left, neck long, shoulder blades down.</div><div>Do not let the head tip forward when the stride gets long.</div>$q$,$q$Foxtrot$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$3013f758-0313-5e7c-99a3-168a429c4baf$q$,$q$Tango head snap — small and sharp$q$,$q$<div>The head action in Tango is a <strong>quick snap</strong> that lasts only for the quick count. If it is too big the whole frame wobbles.</div><div>Keep the left side long and the shoulders level while the head moves.</div>$q$,$q$Tango$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$5e27392b-3130-5f90-8d3c-fb6f044ac526$q$,$q$Quickstep — keeping speed under control$q$,$q$<div>At full tempo I rush the chasses and lose the lowering. Fix: <strong>smaller steps, more swing</strong>. Think about the ankle action and keep the left side long to control the speed on corners.</div>$q$,$q$Quickstep$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$bd971d2a-64c1-5747-9aea-3584df7a183e$q$,$q$Cha Cha — Hockey Stick and Fan connection$q$,$q$<div>Fan: man checks back, lady turns across. Hockey Stick: a clear curve with the lady's line passing the man.</div><div>The connection is through the <strong>hand and body lead</strong>. Don't pull.</div>$q$,$q$Cha Cha$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$597b38c5-99a6-5c74-83b7-b5f82aa12e8d$q$,$q$Rumba — walks with delayed hip action$q$,$q$<div>Rumba walks are <strong>slow</strong>. Step onto the ball, then the heel lowers and the knee straightens before the hip moves.</div><div>If the hip moves first, the walk looks like a Cha Cha.</div>$q$,$q$Rumba$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$dfc199a4-daba-5208-8c6f-596e4dcf1c3f$q$,$q$Samba — Bota Fogos travelling$q$,$q$<div>Bota Fogos: step across, rock and recover while travelling. The hip action should be <strong>smooth, not sharp</strong>. Keep the knees soft.</div>$q$,$q$Samba$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$136cdd54-3804-52cb-b437-52e65b21733a$q$,$q$Jive — bounce and flick$q$,$q$<div>The jive bounce lives in the knees and needs <strong>very quick, light feet</strong>. Chicken walks and flicks are from the knee, not a kick.</div>$q$,$q$Jive$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$59138e28-e0e4-5d91-ba2c-dd10f88fcf0a$q$,$q$Frame and connection — arms$q$,$q$<div>The frame is built from the <strong>back</strong>, not the arms. The elbows stay in front of the shoulders and the upper arms are lifted slightly. The contact is through the right side of the body.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$4b5289fb-aff1-5824-90af-7d7cb76738df$q$,$q$Contra-body movement (CBM) explained$q$,$q$<div>CBM is turning the opposite side of the body towards the moving foot. Used on forward and back steps to create rotation without twisting the legs.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$5127e6ab-c50a-56b4-97c0-236ef96534ab$q$,$q$Sway: what it is and when it is used$q$,$q$<div>Sway is a tilt created by lengthening one side of the body. It is not a lean. It always comes from the ribs and the frame, not the neck.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$dd8cd487-4c8c-586c-8442-7f45e5fe47be$q$,$q$Competition day checklist$q$,$q$<ul><li>Shoes and spare suede brush</li><li>Number and safety pins</li><li>Warm-up routine, 20 minutes</li><li>Water and a light snack</li></ul>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$1691315f-47eb-5f94-ad8b-0d311828cc1c$q$,$q$Dance shoe care$q$,$q$<div>Brush the suede after each session. Never wear the shoes outside. Replace the heel tips when they are worn down to the metal.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$f2809a61-24cb-55f4-8f2c-ba7ea0229977$q$,$q$Warm-up before training$q$,$q$<div>10 minutes light cardio, then mobility for the ankles, hips and shoulders. Finish with slow walks across the floor.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$c855fd1c-62d4-5d80-b711-9f416b599a68$q$,$q$Music tempo reference$q$,$q$<div>Waltz 28–30 bpm, Tango 30–32, Viennese Waltz 58–60, Foxtrot 28–30, Quickstep 50–52. Cha Cha 30, Rumba 25–27, Samba 50–52, Jive 42–44, Paso 60–62.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$d62e538a-e4db-5e51-a670-b46981fe9e03$q$,$q$Performing under pressure$q$,$q$<div>Nerves make me rush. Breathe out on the first count, keep the first steps small and let the music set the tempo.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$a158ca69-0fbe-569c-9ca7-815dd2c08907$q$,$q$Videos to watch this month$q$,$q$<div>Plan: one lecture on technique per week, one competition final to study the entrances and floor craft.</div>$q$,$q$$q$,$q$rafal$q$,$q$PUBLIC$q$),
  ($q$0b9df454-929a-55f1-bc00-9c680c6f4efe$q$,$q$Choreography idea: Waltz short routine$q$,$q$<div>Natural Turn, Hesitation Change, Reverse Turn, Whisk, Chasse from PP. Ends in Promenade Position. Check the corner on the long wall.</div>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PRIVATE$q$),
  ($q$286d866e-14e8-5041-b715-0e96190b5a6a$q$,$q$Private — weak points$q$,$q$<div>Weak points: head drop on lowering, rushing in Quickstep, hip action in Rumba. Need to review with coach.</div>$q$,$q$$q$,$q$rafal$q$,$q$PRIVATE$q$),
  ($q$b67a82de-27f5-5b26-b296-4013de244b62$q$,$q$Another user's private note on head position$q$,$q$<div>Private note: my head position in Waltz needs work. Left side long. This must never be shown to other users.</div>$q$,$q$Waltz$q$,$q$test_user$q$,$q$PRIVATE$q$),
  ($q$8f1f0903-606b-5128-96c2-0511b6fb1405$q$,$q$Another user's private note on rise and fall$q$,$q$<div>Private note about rise and fall and lowering. This must never be shown to other users.</div>$q$,$q$Waltz$q$,$q$test_user$q$,$q$PRIVATE$q$),
  ($q$2a853c15-6775-5dbc-8e10-6709f6e080e9$q$,$q$Head$q$,$q$<div>Head left.</div>$q$,$q$Waltz$q$,$q$rafal$q$,$q$PUBLIC$q$)
  ) AS v(id, name, descr, dance, owner, vis)
 ON CONFLICT (id) DO NOTHING;

-- 4. Comments on the notes
INSERT INTO comment (id, content, author_id, material_id, created_at)
SELECT v.id::uuid, v.content, (SELECT id FROM app_user WHERE username = v.author), v.mid::uuid, now()
  FROM (VALUES
  ($q$3690501b-ca74-5dc7-82ac-71c0499c7c16$q$,$q$<div>Tried it at practice — the <strong>head to the left</strong> finally clicked!</div>$q$,$q$test_user$q$,$q$76a06b4c-6e22-508e-a164-cee4717424e5$q$),
  ($q$a49e7fb9-0ed5-5137-a22b-64e3f19b6aec$q$,$q$<div>Added to the list: film the lowering from the side next lesson.</div>$q$,$q$rafal$q$,$q$cfc019e9-7612-5f31-b5a7-0e3a3669c557$q$),
  ($q$6ec28989-175b-5b7b-a663-d65db923143e$q$,$q$<div>Same problem here, my coach said think "neck long".</div>$q$,$q$test_user$q$,$q$cfc019e9-7612-5f31-b5a7-0e3a3669c557$q$),
  ($q$1a47a4e9-5dba-5a19-a515-3d3f9aa03112$q$,$q$<div>The wall drill helps a lot.</div>$q$,$q$test_user$q$,$q$805e24bf-d16c-5f4b-ae8f-33c90b1cb8fa$q$),
  ($q$f42f035f-904b-5c06-8094-a1019b965879$q$,$q$<div>Remember to keep the toe on step 3.</div>$q$,$q$rafal$q$,$q$4183d3e6-0a8e-5ce3-9c68-ead4de9eefac$q$),
  ($q$529ffcba-fb0b-522a-babd-b10253efe4b1$q$,$q$<div>Great summary. For Natural Turn I also feel the rise from the standing foot first.</div>$q$,$q$test_user$q$,$q$2f8d2fe7-6b36-5c57-9abb-315bc92a5ae3$q$),
  ($q$253891a2-f61e-5754-a2d6-1a5afeac7e81$q$,$q$<div>Heel lead comment: be careful not to stamp the heel.</div>$q$,$q$rafal$q$,$q$13c81077-de9d-5f33-a39d-74219e67da00$q$),
  ($q$51c4e336-261c-5d60-ad20-05cb8f2eea17$q$,$q$<div>Step 3 outside partner is what I always forget.</div>$q$,$q$test_user$q$,$q$5976f255-0990-5c3a-a06a-4ff57c60e28d$q$),
  ($q$9830f9c1-94dd-519b-b87b-98ea2abb275e$q$,$q$<div>Practice the snap in front of a mirror, only for the quick count.</div>$q$,$q$rafal$q$,$q$3013f758-0313-5e7c-99a3-168a429c4baf$q$),
  ($q$5fba6e53-cd56-5724-91a4-147e82fcb06a$q$,$q$<div>Check the tempo, it was probably 52 bpm today.</div>$q$,$q$rafal$q$,$q$5e27392b-3130-5f90-8d3c-fb6f044ac526$q$),
  ($q$cb42948f-e749-509a-a412-5bee03f68510$q$,$q$<div>If the hip moves first it looks like a Cha Cha — exactly!</div>$q$,$q$test_user$q$,$q$597b38c5-99a6-5c74-83b7-b5f82aa12e8d$q$),
  ($q$7be3ad32-b9fe-5b50-819e-8d66403bae20$q$,$q$<div>Flicks from the knee, noted.</div>$q$,$q$test_user$q$,$q$136cdd54-3804-52cb-b437-52e65b21733a$q$),
  ($q$86cc1ef9-ed48-5064-af91-43c88400228e$q$,$q$<div>I would add a spare pair of tights and a hair kit.</div>$q$,$q$test_user$q$,$q$dd8cd487-4c8c-586c-8442-7f45e5fe47be$q$),
  ($q$1e7a6457-43bb-5f0f-82a3-4b7a9d1b8e5e$q$,$q$<div>Replace the heel tips earlier than you think.</div>$q$,$q$rafal$q$,$q$1691315f-47eb-5f94-ad8b-0d311828cc1c$q$),
  ($q$705a16dc-b800-54db-93f4-efaa27af1448$q$,$q$<div>These are the BPM from the federation rules, I think.</div>$q$,$q$test_user$q$,$q$c855fd1c-62d4-5d80-b711-9f416b599a68$q$),
  ($q$f11c2a75-050c-5f6d-b2f2-3e0b66ff7c07$q$,$q$<div>Building the frame from the back changed my lead completely.</div>$q$,$q$test_user$q$,$q$59138e28-e0e4-5d91-ba2c-dd10f88fcf0a$q$),
  ($q$d6b4bebe-9de3-5dc9-9732-3e97712f4c3b$q$,$q$<div>CBM is opposite side towards the moving foot, got it.</div>$q$,$q$rafal$q$,$q$4b5289fb-aff1-5824-90af-7d7cb76738df$q$)
  ) AS v(id, content, author, mid)
 ON CONFLICT (id) DO NOTHING;

COMMIT;
