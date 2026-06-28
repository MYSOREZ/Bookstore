import os, subprocess
os.environ['GIT_SEQUENCE_EDITOR'] = 'true'
subprocess.run(['git', 'rebase', '-i', '--exec', 'git commit --amend --author="Practician <>" --no-edit', '68fe124e5c29c615418160bac32877a39a74aa31'])
