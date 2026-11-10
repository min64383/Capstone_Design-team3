# label_cluster — M56 칸 라벨·라벨 가중 군집 측정

M56 설계 보완판 표 4의 구현 전 점검(A-2~A-4)을 재생 출력과 정답으로 잰다. 알고리즘(칸 과반 라벨, 같은 라벨 이웃 반경)은 Kotlin `core`
(`VoxelMap`, `Cluster.dbscanXZ`)로 옮기고, 여기서는 그 값을 정하는 측정만 한다(README §4.8). `motion_judge`의 읽기 함수를 쓴다.

- `split_cause.py`: A-3 — 한 사람이 둘 이상으로 나온 장을 출력 종류(정지·움직이는 track)로 나눈다.
