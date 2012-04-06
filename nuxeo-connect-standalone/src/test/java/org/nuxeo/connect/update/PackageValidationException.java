/*
 * (C) Copyright 2006-2010 Nuxeo SAS (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     bstefanescu
 */
package org.nuxeo.connect.update;

import org.nuxeo.connect.update.PackageException;
import org.nuxeo.connect.update.ValidationStatus;

/**
 * @author <a href="mailto:bs@nuxeo.com">Bogdan Stefanescu</a>
 * 
 */
public class PackageValidationException extends PackageException {

    private static final long serialVersionUID = 1L;

    protected ValidationStatus status;

    public PackageValidationException(ValidationStatus status) {
        super("validation exception: " + status);
        this.status = status;
    }

    public ValidationStatus getStatus() {
        return status;
    }

}
