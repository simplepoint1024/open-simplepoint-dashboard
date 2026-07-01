#!/usr/bin/bash
pnpm build:host
pnpm build:common
pnpm build:audit
pnpm build:dna
cp -r ./apps/simplepoint-host/dist/* /home/ymsl/Documents/IdeaProjects/open-simplepoint-dashboard/simplepoint-services/simplepoint-service-host/src/main/resources/static/
cp -r ./apps/simplepoint-common/dist/* /home/ymsl/Documents/IdeaProjects/open-simplepoint-dashboard/simplepoint-services/simplepoint-service-common/src/main/resources/static/
cp -r ./apps/simplepoint-audit/dist/* /home/ymsl/Documents/IdeaProjects/open-simplepoint-dashboard/simplepoint-services/simplepoint-service-auditing/src/main/resources/static/
cp -r ./apps/simplepoint-dna/dist/* /home/ymsl/Documents/IdeaProjects/open-simplepoint-dashboard/simplepoint-services/simplepoint-service-dna/src/main/resources/static/